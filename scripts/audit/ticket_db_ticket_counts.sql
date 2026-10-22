-- ticket_db: Đếm số vé thực tế đã được phát hành theo booking_id
-- Dùng để đối chiếu với booking_db.bookings
SELECT 
    t.booking_id,
    COUNT(*) AS issued_tickets
FROM tickets t
GROUP BY t.booking_id;
