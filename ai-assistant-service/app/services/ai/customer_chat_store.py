"""Persist the single customer conversation, keyed by its verified owner."""
import logging
from contextlib import contextmanager
from datetime import datetime, timezone
from functools import lru_cache
from threading import Lock

from bson import ObjectId
from pymongo import MongoClient, ReturnDocument
from pymongo.errors import DuplicateKeyError, PyMongoError
from pymongo.read_concern import ReadConcern
from pymongo.write_concern import WriteConcern

from app.core.config import settings
from app.core.errors import AI_DB_NOT_CONFIGURED, AI_DB_UNAVAILABLE, ai_error

MAX_VISIBLE_MESSAGES = 100
log = logging.getLogger("ai.customer.history")


class CustomerChatChanged(Exception):
    """A concurrent turn or reset changed the history used to generate a reply."""


class CustomerChatStore:
    def __init__(self, client: MongoClient, database: str):
        self.client = client
        self.conversations = client[database].customer_conversations
        self.messages = client[database].customer_messages

    def ensure_indexes(self):
        self.messages.create_index([("owner", 1), ("revision", -1), ("position", -1)])
        self.messages.create_index(
            [("owner", 1), ("request_id", 1)], unique=True,
            partialFilterExpression={"request_id": {"$exists": True}},
        )

    def _transaction(self, operation):
        with self.client.start_session() as session:
            return session.with_transaction(
                operation, read_concern=ReadConcern("snapshot"), write_concern=WriteConcern("majority"),
            )

    def version(self, owner: str) -> tuple[int, int]:
        conversation = self.conversations.find_one({"_id": owner}, {"revision": 1, "generation": 1}) or {}
        return conversation.get("revision", 0), conversation.get("generation", 0)

    def latest_messages(self, owner: str, limit: int, *, revision: int, before: str | None = None) -> list[dict]:
        query = {"owner": owner, "revision": {"$lte": revision}}
        if before:
            anchor = self.messages.find_one({"_id": ObjectId(before), **query}, {"revision": 1, "position": 1})
            if not anchor:
                return []
            query["$or"] = [
                {"revision": {"$lt": anchor["revision"]}},
                {"revision": anchor["revision"], "position": {"$lt": anchor["position"]}},
            ]
        cursor = self.messages.find(query).sort([("revision", -1), ("position", -1)]).limit(limit)
        return list(reversed(list(cursor)))

    def find_reply(self, owner: str, request_id: str) -> dict | None:
        return self.messages.find_one({"owner": owner, "request_id": request_id})

    def detail(self, owner: str, before: str | None = None) -> dict:
        revision, generation = self.version(owner)
        rows = self.latest_messages(owner, MAX_VISIBLE_MESSAGES + 1, revision=revision, before=before)
        page = rows[-MAX_VISIBLE_MESSAGES:]
        return {
            "generation": generation,
            "older_cursor": str(page[0]["_id"]) if len(rows) > MAX_VISIBLE_MESSAGES else None,
            "messages": [
                {"id": str(row["_id"]), "role": row["role"], "content": row["content"],
                 "created_at": row["created_at"], "system_actions": row.get("system_actions", [])}
                for row in page
            ],
        }

    def import_history(self, owner: str, messages: list[dict], legacy_hash: str) -> bool:
        if not messages:
            return False
        existing = self.conversations.find_one({"_id": owner}, {"legacy_hash": 1})
        if existing:
            return existing.get("legacy_hash") == legacy_hash

        def import_once(session):
            self.conversations.insert_one({"_id": owner, "revision": 1, "legacy_hash": legacy_hash}, session=session)
            self.messages.insert_many([
                {**message, "owner": owner, "revision": 1, "position": index}
                for index, message in enumerate(messages)
            ], session=session)

        try:
            self._transaction(import_once)
        except DuplicateKeyError:
            # Another tab imported, sent or reset while this browser was loading.
            return self.conversations.find_one({"_id": owner, "legacy_hash": legacy_hash}) is not None
        return True

    def reset(self, owner: str) -> int:
        def clear(session):
            conversation = self.conversations.find_one_and_update(
                {"_id": owner}, {"$inc": {"revision": 1, "generation": 1}},
                upsert=True, return_document=ReturnDocument.AFTER, session=session,
            )
            self.messages.delete_many({"owner": owner}, session=session)
            return conversation["generation"]

        try:
            return self._transaction(clear)
        except DuplicateKeyError:
            return self._transaction(clear)

    def append_turn(self, *, owner: str, revision: int, prompt: str, display_message: str,
                    reply: str, actions: list, request_id: str, request_hash: str):
        now = datetime.now(timezone.utc)
        fields = {"owner": owner, "revision": revision + 1, "created_at": now}
        user = {**fields, "_id": ObjectId(), "position": 0, "role": "user", "content": display_message, "prompt": prompt}
        assistant = {**fields, "_id": ObjectId(), "position": 1, "role": "assistant", "content": reply,
                     "system_actions": actions, "request_id": request_id, "request_hash": request_hash}

        def append(session):
            if revision == 0:
                self.conversations.insert_one({"_id": owner, "revision": 1}, session=session)
            else:
                result = self.conversations.update_one(
                    {"_id": owner, "revision": revision}, {"$inc": {"revision": 1}}, session=session,
                )
                if not result.matched_count:
                    raise CustomerChatChanged
            self.messages.insert_many([user, assistant], session=session)

        self._transaction(append)


@lru_cache(maxsize=1)
def _connect(uri: str, database: str) -> CustomerChatStore:
    client = MongoClient(uri, tz_aware=True, serverSelectionTimeoutMS=2500, timeoutMS=5000)
    try:
        store = CustomerChatStore(client, database)
        store.ensure_indexes()
        return store
    except Exception:
        client.close()
        raise


_connect_lock = Lock()


@contextmanager
def customer_chat_store():
    if not settings.mongodb_uri:
        raise ai_error(AI_DB_NOT_CONFIGURED)
    try:
        with _connect_lock:
            store = _connect(settings.mongodb_uri, settings.mongodb_database)
        yield store
    except PyMongoError as failure:
        log.warning("Customer chat storage failed: %s (%s)", type(failure).__name__, getattr(failure, "code", None))
        raise ai_error(AI_DB_UNAVAILABLE, "Kho lịch sử trò chuyện tạm thời không truy cập được.") from failure
