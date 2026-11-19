-- Thêm loại ngoại lệ thanh toán AMOUNT_MISMATCH (số tiền đã thanh toán lệch tổng tiền của đơn).
-- Giữ danh sách kind trong CHECK bằng enum PaymentExceptionKind của booking-service.
-- Chạy lại nhiều lần vẫn an toàn.

ALTER TABLE payment_exceptions DROP CONSTRAINT IF EXISTS payment_exceptions_kind_check;
ALTER TABLE payment_exceptions
    ADD CONSTRAINT payment_exceptions_kind_check
    CHECK (kind IN ('LATE_PAYMENT_NO_STOCK', 'LATE_PAYMENT_SEATS_UNAVAILABLE', 'LATE_PAYMENT_UNEXPECTED_STATUS', 'ISSUANCE_FAILED', 'AMOUNT_MISMATCH'));
