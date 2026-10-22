-- booking_db: Danh sách các đơn hàng đã thanh toán (PAID hoặc CONFIRMED)
-- Dùng để đối chiếu với ticket_db nhằm phát hiện đơn bị thiếu vé
SELECT 
    b.id AS booking_id,
    b.booking_code,
    b.event_id,
    b.showtime_id,
    b.status,
    b.created_at,
    COALESCE(SUM(bi.quantity), 0) AS expected_tickets
FROM bookings b
LEFT JOIN booking_items bi ON b.id = bi.booking_id
WHERE b.status IN ('PAID', 'CONFIRMED')
GROUP BY b.id, b.booking_code, b.event_id, b.showtime_id, b.status, b.created_at
ORDER BY b.created_at DESC;
