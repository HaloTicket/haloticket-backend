"""Copy and reconcile the legacy notification tables before Mongo cutover.

Run this tool with the notification writers and consumers stopped. Use a
read-only PostgreSQL account and a Mongo target that is not serving traffic.
Install psycopg[binary], pymongo and tzdata in a dedicated Python environment.
The read-only modes are inventory and verify. Apply replaces matching IDs and
is resumable before opening the target to writers.
"""

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from zoneinfo import ZoneInfo


TABLES = {
    "notification_templates": """
        SELECT id AS "_id", type, channel, subject_template AS "subjectTemplate",
               body_template AS "bodyTemplate", status, created_at AS "createdAt",
               updated_at AS "updatedAt"
        FROM public.notification_templates ORDER BY id
    """,
    "notifications": """
        SELECT id AS "_id", event_id AS "eventId", template_id AS "templateId",
               user_id AS "userId", recipient_email AS "recipientEmail",
               variables_json AS "variablesJson", title, message, type,
               read_status AS "readStatus", channel, status,
               created_at AS "createdAt", sent_at AS "sentAt"
        FROM public.notifications ORDER BY id
    """,
}


def normalize_document(row, source_zone):
    document = dict(row)
    for key, value in row.items():
        if isinstance(value, datetime):
            aware = value if value.tzinfo is not None else value.replace(tzinfo=source_zone)
            utc = aware.astimezone(timezone.utc)
            document[key] = utc.replace(microsecond=(utc.microsecond // 1000) * 1000)
    if "userId" in document:
        if not (document.get("eventId") or "").strip():
            document.pop("eventId", None)
        document["templateId"] = document.get("templateId") or None
        document["priority"] = "NORMAL"
    return document


def same_documents(source, target):
    return {row["_id"]: row for row in source} == {row["_id"]: row for row in target}


def batches(connection, collection, batch_size):
    with connection.cursor(name=f"migration_{collection}") as cursor:
        cursor.execute(TABLES[collection])
        while rows := cursor.fetchmany(batch_size):
            yield rows


def migrate(connection, database, mode, batch_size, source_zone):
    from pymongo import ReplaceOne

    report = {}
    for name in TABLES:
        count = read = templates_referenced = 0
        for rows in batches(connection, name, batch_size):
            documents = [normalize_document(row, source_zone) for row in rows]
            count += len(documents)
            read += sum(row.get("readStatus") is True for row in documents)
            template_ids = {row["templateId"] for row in documents if row.get("templateId")}
            templates_referenced += sum(bool(row.get("templateId")) for row in documents)
            if mode == "inventory":
                continue

            template_count = database.notification_templates.count_documents(
                {"_id": {"$in": list(template_ids)}}
            ) if template_ids else 0
            if template_count != len(template_ids):
                raise ValueError("A notification references an unmigrated template.")
            target = database[name]
            if mode == "apply":
                target.bulk_write(
                    [ReplaceOne({"_id": row["_id"]}, row, upsert=True) for row in documents],
                    ordered=False,
                )
            ids = [row["_id"] for row in documents]
            if not same_documents(documents, target.find({"_id": {"$in": ids}})):
                raise ValueError(f"{name}: field-by-field reconciliation failed.")

        report[name] = {"sourceCount": count, "readCount": read, "withTemplate": templates_referenced}
        if mode != "inventory":
            target_count = database[name].count_documents({})
            if target_count != count:
                raise ValueError(f"{name}: source count {count}, target count {target_count}.")
            report[name]["targetCount"] = target_count
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("inventory", "apply", "verify"))
    parser.add_argument("--source-zone", required=True, help="IANA zone of legacy timestamps")
    parser.add_argument("--mongo-database", default="haloticket_notifications")
    parser.add_argument("--batch-size", type=int, default=500)
    args = parser.parse_args(argv)
    if not 1 <= args.batch_size <= 1000:
        parser.error("--batch-size must be between 1 and 1000")

    postgres_dsn = os.environ.get("NOTIFICATION_MIGRATION_POSTGRES_DSN")
    mongo_uri = os.environ.get("NOTIFICATION_MIGRATION_MONGO_URI")
    if not postgres_dsn or (args.mode != "inventory" and not mongo_uri):
        parser.error("Set NOTIFICATION_MIGRATION_POSTGRES_DSN and, for apply/verify, NOTIFICATION_MIGRATION_MONGO_URI.")

    try:
        import psycopg
        from psycopg.rows import dict_row
        from pymongo import MongoClient
    except ImportError:
        parser.error("Install psycopg[binary], pymongo and tzdata in your migration environment.")

    try:
        source_zone = timezone.utc if args.source_zone == "UTC" else ZoneInfo(args.source_zone)
        with psycopg.connect(postgres_dsn, connect_timeout=10, row_factory=dict_row) as source:
            source.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
            if args.mode == "inventory":
                report = migrate(source, None, args.mode, args.batch_size, source_zone)
            else:
                with MongoClient(mongo_uri, tz_aware=True, serverSelectionTimeoutMS=10000) as mongo:
                    report = migrate(source, mongo[args.mongo_database], args.mode, args.batch_size, source_zone)
    except ValueError as error:
        print(f"Migration stopped. {error}", file=sys.stderr)
        return 1
    except Exception as error:
        print(f"Migration stopped ({type(error).__name__}). Check database access, schema and time zone.", file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
