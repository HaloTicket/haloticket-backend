-- Apply to ticket_db. RUN BY HAND AFTER THE CLEANUP SCRIPT (20261031_ticket_types_drop_master_rows.sql). Not run by the assistant.
--
-- Natural key of a ticket type: (event_id, COALESCE(showtime_id, ''), lower(btrim(name))). The sync code looks a row up by this
-- key before creating one; this index makes the database refuse a duplicate when two saves race.
-- Hibernate ddl-auto=update cannot create expression indexes, so it is only created here. Safe to run more than once.
-- Take a pg_dump of ticket_db first.

-- PRE-CHECK (read only): duplicate groups that would make CREATE UNIQUE INDEX fail. Expect no rows.
-- If rows come back, merge or delete the extra rows (keep the one that has tickets or the highest sold count) before continuing.
--   SELECT event_id, COALESCE(showtime_id, '') AS showtime_key, lower(btrim(name)) AS name_key,
--          count(*) AS rows, array_agg(id ORDER BY created_at) AS ids
--   FROM ticket_types
--   GROUP BY event_id, COALESCE(showtime_id, ''), lower(btrim(name))
--   HAVING count(*) > 1;

BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ticket_types_natural_key
    ON ticket_types (event_id, COALESCE(showtime_id, ''), lower(btrim(name)));

COMMIT;

-- VERIFY (read only). Expect one row.
--   SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'ticket_types' AND indexname = 'uk_ticket_types_natural_key';

-- ROLLBACK (only if needed):
--   DROP INDEX IF EXISTS uk_ticket_types_natural_key;
