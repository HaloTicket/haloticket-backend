-- Run against notification_db. Preserve custom subjects configured by administrators.
UPDATE notification_templates
SET subject_template = 'HaloTicket - Thanh toán thành công đơn {{bookingCode}}'
WHERE type = 'TICKET_CONFIRMATION' AND channel = 'EMAIL'
  AND subject_template = 'Your HaloTicket order is confirmed';
