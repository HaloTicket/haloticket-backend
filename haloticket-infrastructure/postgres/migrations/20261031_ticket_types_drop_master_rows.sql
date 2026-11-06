-- Apply to ticket_db. TAKE A pg_dump OF ticket_db FIRST. Not run by the assistant; run by hand, then run
-- 20261031_ticket_types_unique_natural_key.sql.
--
-- Removes the showtime-less "master" ticket_types rows (showtime_id NULL or '') of events that already have per-showtime rows,
-- when nothing references them: no tickets, no stock reservations, no seat zones. Rows that are still referenced are kept and
-- listed by the second PRE-CHECK so they can be handled by hand. Safe to run more than once.

-- PRE-CHECK 1 (read only): master rows this script will delete.
--   SELECT tt.id, tt.event_id, tt.name, tt.initial_quantity, tt.total_quantity, tt.reserved_quantity
--   FROM ticket_types tt
--   WHERE (tt.showtime_id IS NULL OR tt.showtime_id = '')
--     AND EXISTS (SELECT 1 FROM ticket_types s WHERE s.event_id = tt.event_id AND s.showtime_id IS NOT NULL AND s.showtime_id <> '')
--     AND NOT EXISTS (SELECT 1 FROM tickets t WHERE t.ticket_type_id = tt.id)
--     AND NOT EXISTS (SELECT 1 FROM ticket_stock_reservations r WHERE r.ticket_type_id = tt.id)
--     AND NOT EXISTS (SELECT 1 FROM seat_zones z WHERE z.ticket_type_id = tt.id);

-- PRE-CHECK 2 (read only): master rows that stay because something references them (decide by hand). Expect no rows ideally.
--   SELECT tt.id, tt.event_id, tt.name,
--          (SELECT count(*) FROM tickets t WHERE t.ticket_type_id = tt.id) AS tickets,
--          (SELECT count(*) FROM ticket_stock_reservations r WHERE r.ticket_type_id = tt.id) AS reservations,
--          (SELECT count(*) FROM seat_zones z WHERE z.ticket_type_id = tt.id) AS seat_zones
--   FROM ticket_types tt
--   WHERE (tt.showtime_id IS NULL OR tt.showtime_id = '')
--     AND EXISTS (SELECT 1 FROM ticket_types s WHERE s.event_id = tt.event_id AND s.showtime_id IS NOT NULL AND s.showtime_id <> '')
--     AND (EXISTS (SELECT 1 FROM tickets t WHERE t.ticket_type_id = tt.id)
--          OR EXISTS (SELECT 1 FROM ticket_stock_reservations r WHERE r.ticket_type_id = tt.id)
--          OR EXISTS (SELECT 1 FROM seat_zones z WHERE z.ticket_type_id = tt.id));

BEGIN;

DELETE FROM ticket_types tt
WHERE (tt.showtime_id IS NULL OR tt.showtime_id = '')
  AND EXISTS (SELECT 1 FROM ticket_types s WHERE s.event_id = tt.event_id AND s.showtime_id IS NOT NULL AND s.showtime_id <> '')
  AND NOT EXISTS (SELECT 1 FROM tickets t WHERE t.ticket_type_id = tt.id)
  AND NOT EXISTS (SELECT 1 FROM ticket_stock_reservations r WHERE r.ticket_type_id = tt.id)
  AND NOT EXISTS (SELECT 1 FROM seat_zones z WHERE z.ticket_type_id = tt.id);

COMMIT;

-- VERIFY (read only): events that still have both master and per-showtime rows (only the referenced ones from PRE-CHECK 2).
--   SELECT event_id, count(*) FILTER (WHERE showtime_id IS NULL OR showtime_id = '') AS master_rows,
--          count(*) FILTER (WHERE showtime_id IS NOT NULL AND showtime_id <> '') AS showtime_rows
--   FROM ticket_types GROUP BY event_id HAVING count(*) FILTER (WHERE showtime_id IS NULL OR showtime_id = '') > 0;

-- ROLLBACK: restore from the pg_dump taken before running.
