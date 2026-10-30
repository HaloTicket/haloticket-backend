-- ticket_db: đối soát kho vé với vé thực tế. CHỈ ĐỌC (SELECT), chạy tay trên ticket_db.
--   psql -U <user> -d ticket_db -f scripts/audit/ticket_db_stock_reconciliation.sql
--
-- Cột của ticket_types:  initial_quantity = số phát hành, total_quantity = số CÒN LẠI trong kho (chưa trừ phần đang giữ),
--                        reserved_quantity = phần đang giữ cho đơn chưa thanh toán.
-- Các con số API trả về:  remaining = total, held = reserved, available = max(0, total - reserved),
--                        sold = max(0, initial - total) (rỗng khi initial_quantity rỗng, dữ liệu cũ).
-- "Vé bán lần đầu" = tickets có acquisition_type rỗng hoặc PRIMARY, status khác REVOKED (cùng quy ước với view v_ticket_sales_daily).
BEGIN READ ONLY;

-- A. Từng hạng vé: kho so với vé thực tế. Mọi dòng có lệch_* khác 0 hoặc ghi_chu khác rỗng cần xem.
WITH issued AS (
    SELECT t.ticket_type_id, COUNT(*) AS issued_primary
      FROM tickets t
     WHERE t.status <> 'REVOKED' AND (t.acquisition_type IS NULL OR t.acquisition_type = 'PRIMARY')
     GROUP BY t.ticket_type_id
), held AS (
    SELECT r.ticket_type_id, COALESCE(SUM(r.quantity), 0) AS held_in_reservations
      FROM ticket_stock_reservations r
     WHERE r.status = 'HELD'
     GROUP BY r.ticket_type_id
)
SELECT tt.event_id,
       tt.showtime_id,
       tt.id                                   AS ticket_type_id,
       tt.name,
       tt.ticket_type_status                   AS status,
       tt.initial_quantity                     AS initial,
       tt.total_quantity                       AS remaining,
       COALESCE(tt.reserved_quantity, 0)       AS held,
       GREATEST(0, COALESCE(tt.total_quantity, 0) - COALESCE(tt.reserved_quantity, 0)) AS available,
       CASE WHEN tt.initial_quantity IS NULL OR tt.total_quantity IS NULL THEN NULL
            ELSE GREATEST(0, tt.initial_quantity - tt.total_quantity) END AS sold_by_stock,
       COALESCE(i.issued_primary, 0)           AS issued_tickets,
       COALESCE(h.held_in_reservations, 0)     AS held_by_reservations,
       -- đã bán theo kho trừ vé đã phát hành: > 0 kho đã trừ nhiều hơn số vé (thiếu vé), < 0 có vé thừa so với kho
       CASE WHEN tt.initial_quantity IS NULL OR tt.total_quantity IS NULL THEN NULL
            ELSE GREATEST(0, tt.initial_quantity - tt.total_quantity) - COALESCE(i.issued_primary, 0) END AS lech_kho_so_voi_ve,
       -- reserved_quantity so với tổng các phiếu giữ đang HELD
       COALESCE(tt.reserved_quantity, 0) - COALESCE(h.held_in_reservations, 0) AS lech_giu_so_voi_phieu,
       CONCAT_WS('; ',
           CASE WHEN tt.initial_quantity IS NULL THEN 'initial_quantity rỗng (dữ liệu cũ): không tính được đã bán' END,
           CASE WHEN tt.total_quantity < 0 THEN 'total_quantity âm' END,
           CASE WHEN COALESCE(tt.reserved_quantity, 0) > COALESCE(tt.total_quantity, 0) THEN 'đang giữ nhiều hơn số còn lại' END,
           CASE WHEN tt.initial_quantity IS NOT NULL AND tt.total_quantity > tt.initial_quantity THEN 'còn lại lớn hơn phát hành (ban tổ chức đã tăng kho, hoặc đồng bộ ghi đè total_quantity)' END
       )                                       AS ghi_chu
  FROM ticket_types tt
  LEFT JOIN issued i ON i.ticket_type_id = tt.id
  LEFT JOIN held   h ON h.ticket_type_id = tt.id
 ORDER BY (COALESCE(tt.reserved_quantity, 0) - COALESCE(h.held_in_reservations, 0)) <> 0 DESC,
          ABS(COALESCE(GREATEST(0, tt.initial_quantity - tt.total_quantity) - COALESCE(i.issued_primary, 0), 0)) DESC,
          tt.event_id, tt.name;

-- B. Số vé theo trạng thái cho từng hạng vé (cùng tập vé với phần A, kèm các trạng thái còn lại).
SELECT tt.event_id, tt.id AS ticket_type_id, tt.name,
       COUNT(t.id) FILTER (WHERE t.status = 'VALID')              AS valid,
       COUNT(t.id) FILTER (WHERE t.status = 'USED')               AS used,
       COUNT(t.id) FILTER (WHERE t.status = 'TRANSFERED')         AS transfered,
       COUNT(t.id) FILTER (WHERE t.status = 'LOCKED_FOR_RESALE')  AS locked_for_resale,
       COUNT(t.id) FILTER (WHERE t.status = 'LOCKED_FOR_TRANSFER') AS locked_for_transfer,
       COUNT(t.id) FILTER (WHERE t.status = 'REVOKED')            AS revoked,
       COUNT(t.id)                                                AS all_tickets
  FROM ticket_types tt
  LEFT JOIN tickets t ON t.ticket_type_id = tt.id
 GROUP BY tt.event_id, tt.id, tt.name
 ORDER BY tt.event_id, tt.name;

-- C. Đơn từng THỪA hoặc THIẾU vé: số lượng đã "tiêu thụ" ở phiếu giữ kho (CONSUMED) so với vé bán lần đầu thực tế của đơn
--    theo từng hạng vé. Chỉ có dữ liệu cho đơn đi qua cơ chế giữ kho GA; đơn cũ hơn (hoặc có sơ đồ ghế) không có phiếu, xem phần D.
--    lech > 0: phát hành thiếu vé so với phần đã trừ kho; lech < 0: phát hành thừa vé.
WITH consumed AS (
    SELECT r.booking_id, r.ticket_type_id, SUM(r.quantity) AS consumed_quantity
      FROM ticket_stock_reservations r
     WHERE r.status = 'CONSUMED'
     GROUP BY r.booking_id, r.ticket_type_id
), issued AS (
    SELECT t.booking_id, t.ticket_type_id, COUNT(*) AS issued_tickets
      FROM tickets t
     WHERE t.status <> 'REVOKED' AND (t.acquisition_type IS NULL OR t.acquisition_type = 'PRIMARY') AND t.booking_id IS NOT NULL
     GROUP BY t.booking_id, t.ticket_type_id
)
SELECT c.booking_id, c.ticket_type_id, c.consumed_quantity, COALESCE(i.issued_tickets, 0) AS issued_tickets,
       c.consumed_quantity - COALESCE(i.issued_tickets, 0) AS lech
  FROM consumed c
  LEFT JOIN issued i ON i.booking_id = c.booking_id AND i.ticket_type_id = c.ticket_type_id
 WHERE c.consumed_quantity <> COALESCE(i.issued_tickets, 0)
 ORDER BY ABS(c.consumed_quantity - COALESCE(i.issued_tickets, 0)) DESC, c.booking_id;

-- D. Vé theo đơn (mọi đơn có vé, kể cả đơn cũ). Đối chiếu bằng tay với booking_db: chạy booking_db_paid_bookings.sql
--    (expected_tickets = tổng quantity của các dòng đơn) rồi so với issued_tickets ở đây theo booking_id.
--    Đơn PAID mà không xuất hiện ở đây hoặc có issued_tickets < expected_tickets là đơn thiếu vé; issued_tickets > expected_tickets là thừa vé.
SELECT t.booking_id, COUNT(*) AS issued_tickets
  FROM tickets t
 WHERE t.status <> 'REVOKED' AND (t.acquisition_type IS NULL OR t.acquisition_type = 'PRIMARY') AND t.booking_id IS NOT NULL
 GROUP BY t.booking_id
 ORDER BY t.booking_id;

ROLLBACK;
