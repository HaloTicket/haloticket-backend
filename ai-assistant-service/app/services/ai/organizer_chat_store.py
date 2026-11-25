"""Mongo persistence for organizer conversations. Every read and write is owner-scoped."""
import logging
from contextlib import contextmanager
from datetime import datetime, timezone
from functools import lru_cache
from threading import Lock
from uuid import uuid4

from bson import ObjectId
from pymongo import MongoClient, ReturnDocument
from pymongo.errors import DuplicateKeyError, PyMongoError
from pymongo.read_concern import ReadConcern
from pymongo.write_concern import WriteConcern

from app.core.config import settings
from app.core.errors import AI_DB_NOT_CONFIGURED, AI_DB_UNAVAILABLE, ai_error


class ConversationChanged(Exception):
    """A turn changed between reading its history and committing an answer."""


log = logging.getLogger("ai.organizer.history")


def _id_filter(owner: str, conversation_id: str) -> dict:
    return {"owner_sub": owner, "_id": conversation_id}


def _partition(owner: str, scope: str, event_id: str | None) -> dict:
    return {"owner_sub": owner, "scope": scope, "event_id": event_id}


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class OrganizerChatStore:
    def __init__(self, client: MongoClient, database: str):
        self.client = client
        self.conversations = client[database].organizer_conversations
        self.messages = client[database].organizer_messages

    def ensure_indexes(self):
        scope_keys = [("owner_sub", 1), ("scope", 1), ("event_id", 1)]
        self.conversations.create_index(scope_keys + [("is_pinned", -1), ("updated_at", -1), ("_id", -1)])
        self.messages.create_index([("conversation_id", 1), ("owner_sub", 1), ("revision", 1), ("_id", 1)])
        self.messages.create_index(
            scope_keys + [("request_id", 1)],
            unique=True,
            partialFilterExpression={"request_id": {"$exists": True}},
        )

    def list_conversations(self, owner: str, scope: str, event_id: str | None) -> list[dict]:
        return list(self.conversations.find(_partition(owner, scope, event_id)).sort(
            [("is_pinned", -1), ("updated_at", -1), ("_id", -1)]
        ))

    def _new_conversation(self, owner: str, scope: str, event_id: str | None, now: datetime) -> dict:
        return {
            **_partition(owner, scope, event_id),
            "_id": str(uuid4()), "title": "", "is_pinned": False,
            "created_at": now, "updated_at": now, "revision": 0,
            "view_event_ids": [], "finance_event_ids": [],
        }

    def create_conversation(self, owner: str, scope: str, event_id: str | None) -> dict:
        conversation = self._new_conversation(owner, scope, event_id, _utc_now())
        self.conversations.insert_one(conversation)
        return conversation

    def get_conversation(self, owner: str, conversation_id: str) -> dict | None:
        return self.conversations.find_one(_id_filter(owner, conversation_id))

    def get_messages(self, conversation: dict, *, limit: int | None = None) -> list[dict]:
        # Revisions make reads consistent with the permission snapshot, even if another turn just finished.
        cursor = self.messages.find({
            "conversation_id": conversation["_id"], "owner_sub": conversation["owner_sub"],
            "revision": {"$lte": conversation["revision"]},
        }, projection=None if limit is None else {"role": 1, "content": 1})
        if limit is None:
            return list(cursor.sort([("revision", 1), ("_id", 1)]))
        return list(reversed(list(cursor.sort([("revision", -1), ("_id", -1)]).limit(limit))))

    def find_reply(self, owner: str, scope: str, event_id: str | None, request_id: str) -> dict | None:
        return self.messages.find_one({**_partition(owner, scope, event_id), "request_id": request_id})

    def set_pinned(self, conversation: dict, pinned: bool) -> dict | None:
        return self.conversations.find_one_and_update(
            {**_id_filter(conversation["owner_sub"], conversation["_id"]), "revision": conversation["revision"]},
            {"$set": {"is_pinned": pinned}}, return_document=ReturnDocument.AFTER,
        )

    def _transaction(self, operation):
        with self.client.start_session() as session:
            return session.with_transaction(
                operation, read_concern=ReadConcern("snapshot"), write_concern=WriteConcern("majority"),
            )

    def delete_conversation(self, owner: str, conversation_id: str) -> bool:
        def delete(session):
            result = self.conversations.delete_one(_id_filter(owner, conversation_id), session=session)
            if not result.deleted_count:
                return False
            self.messages.delete_many({"owner_sub": owner, "conversation_id": conversation_id}, session=session)
            return True

        return self._transaction(delete)

    def clear_conversations(self, owner: str, scope: str, event_id: str | None):
        query = _partition(owner, scope, event_id)

        def clear(session):
            self.conversations.delete_many(query, session=session)
            self.messages.delete_many(query, session=session)

        self._transaction(clear)

    def append_turn(
        self, *, owner: str, scope: str, event_id: str | None, conversation: dict | None,
        question: str, response: dict, events: list[dict], request_id: str | None, request_hash: str,
    ) -> str:
        now = _utc_now()
        snapshot = conversation or self._new_conversation(owner, scope, event_id, now)
        conversation_id = snapshot["_id"]
        updated = {
            "title": snapshot["title"] or question[:60], "updated_at": now, "revision": snapshot["revision"] + 1,
            "view_event_ids": sorted(set(snapshot["view_event_ids"]) | {row["event_id"] for row in events}),
            "finance_event_ids": sorted(set(snapshot["finance_event_ids"]) |
                                        {row["event_id"] for row in events if row.get("can_view_finance")}),
        }
        fields = {**_partition(owner, scope, event_id), "conversation_id": conversation_id,
                  "created_at": now, "revision": updated["revision"]}
        user = {**fields, "_id": ObjectId(), "role": "user", "content": question, "status": "ok"}
        assistant = {**fields, "_id": ObjectId(), "role": "assistant", "content": response["reply"],
                     "status": response["status"], "payload": {
                         key: value for key, value in response.items() if key not in {"reply", "status"}
                     }}
        if request_id:
            assistant.update(request_id=request_id, request_hash=request_hash)

        def append(session):
            if conversation is None:
                self.conversations.insert_one({**snapshot, **updated}, session=session)
            else:
                result = self.conversations.update_one(
                    {**_id_filter(owner, conversation_id), "revision": snapshot["revision"]},
                    {"$set": updated}, session=session,
                )
                if result.matched_count != 1:
                    raise ConversationChanged
            self.messages.insert_one(assistant, session=session)
            self.messages.insert_one(user, session=session)

        self._transaction(append)
        return conversation_id


@lru_cache(maxsize=1)
def _connect(uri: str, database: str) -> OrganizerChatStore:
    client = MongoClient(uri, tz_aware=True, serverSelectionTimeoutMS=2500, timeoutMS=5000)
    try:
        store = OrganizerChatStore(client, database)
        store.ensure_indexes()
        return store
    except Exception:
        client.close()
        raise


_connect_lock = Lock()


@contextmanager
def organizer_chat_store():
    if not settings.mongodb_uri:
        raise ai_error(AI_DB_NOT_CONFIGURED)
    try:
        with _connect_lock:
            store = _connect(settings.mongodb_uri, settings.mongodb_database)
        yield store
    except DuplicateKeyError:
        raise
    except PyMongoError as failure:
        log.warning("Organizer chat storage failed: %s (%s)", type(failure).__name__, getattr(failure, "code", None))
        raise ai_error(AI_DB_UNAVAILABLE, "Kho lịch sử trò chuyện tạm thời không truy cập được.") from failure
