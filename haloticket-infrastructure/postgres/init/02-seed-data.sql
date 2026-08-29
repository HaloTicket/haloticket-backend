-- SQL Seed Data Script for HaloTicket Microservices Platform
-- Creates tables (if not exist) and inserts initial sample data for each service DB

-- ============================================================================
-- 1. USER DB (user_db)
-- ============================================================================
\c user_db;

CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(255) PRIMARY KEY,
    keycloak_id VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    full_name VARCHAR(255),
    phone_number VARCHAR(255),
    avatar_url VARCHAR(255),
    is_active BOOLEAN,
    gender VARCHAR(50),
    status VARCHAR(50),
    last_login_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id VARCHAR(255) REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE IF NOT EXISTS ekyc_profiles (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) REFERENCES users(id) ON DELETE CASCADE,
    document_type VARCHAR(50),
    document_number VARCHAR(255),
    front_image_url VARCHAR(255),
    back_image_url VARCHAR(255),
    face_video_url VARCHAR(255),
    bank_name VARCHAR(255),
    bank_account_name VARCHAR(255),
    bank_account_number VARCHAR(255),
    status VARCHAR(50),
    approved_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

INSERT INTO users (id, keycloak_id, email, full_name, phone_number, avatar_url, is_active, gender, status, created_at, updated_at)
VALUES 
('11111111-1111-1111-1111-111111111111', 'kc-admin-001', 'admin@haloticket.vn', 'Trần Long Vũ (Admin)', '0901234567', 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('22222222-2222-2222-2222-222222222222', 'kc-organizer-001', 'organizer@haloticket.vn', 'Trần Công Minh (Halo Entertainment)', '0912345678', 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('33333333-3333-3333-3333-333333333333', 'kc-staff-001', 'staff@haloticket.vn', 'Nguyễn Văn Soát Vé', '0987654321', 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('44444444-4444-4444-4444-444444444444', 'kc-customer-001', 'customer@haloticket.vn', 'Lê Thị Mua Vé', '0933445566', 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200', true, 'FEMALE', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_roles (user_id, role)
VALUES 
('11111111-1111-1111-1111-111111111111', 'ADMIN'),
('22222222-2222-2222-2222-222222222222', 'ORGANIZER'),
('22222222-2222-2222-2222-222222222222', 'CUSTOMER'),
('33333333-3333-3333-3333-333333333333', 'STAFF'),
('44444444-4444-4444-4444-444444444444', 'CUSTOMER')
ON CONFLICT DO NOTHING;

INSERT INTO ekyc_profiles (id, user_id, document_type, document_number, front_image_url, back_image_url, bank_name, bank_account_name, bank_account_number, status, approved_at, created_at, updated_at)
VALUES
('ekyc-org-001', '22222222-2222-2222-2222-222222222222', 'BUSINESS_LICENSE', '0312345678', 'https://example.com/ekyc/license_front.jpg', 'https://example.com/ekyc/license_back.jpg', 'MBBank', 'CONG TY HALO ENTERTAINMENT', '999988887777', 'APPROVED', NOW() - INTERVAL '30 days', NOW(), NOW()),
('ekyc-cust-001', '44444444-4444-4444-4444-444444444444', 'ID_CARD', '079200001234', 'https://example.com/ekyc/cccd_front.jpg', 'https://example.com/ekyc/cccd_back.jpg', 'Vietcombank', 'LE THI MUA VE', '1012345678', 'APPROVED', NOW() - INTERVAL '15 days', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- 2. EVENT DB (event_db)
-- ============================================================================
\c event_db;

CREATE TABLE IF NOT EXISTS event_categories (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) UNIQUE NOT NULL,
    description TEXT,
    status VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS event_organizers (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    description TEXT,
    role VARCHAR(50),
    status VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS events (
    id VARCHAR(255) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    banner_url VARCHAR(255),
    thumbnail_url VARCHAR(255),
    location_name VARCHAR(255),
    address VARCHAR(255),
    event_status VARCHAR(50),
    event_approval_status VARCHAR(50),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    sale_start_time TIMESTAMP,
    sale_end_time TIMESTAMP,
    category_id VARCHAR(255) REFERENCES event_categories(id),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS event_organizer_mapping (
    event_id VARCHAR(255) REFERENCES events(id) ON DELETE CASCADE,
    organizer_id VARCHAR(255) REFERENCES event_organizers(id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, organizer_id)
);

CREATE TABLE IF NOT EXISTS staff_assignments (
    id VARCHAR(255) PRIMARY KEY,
    event_id VARCHAR(255) REFERENCES events(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    status VARCHAR(50),
    assigned_at TIMESTAMP,
    revoked_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS vouchers (
    id VARCHAR(255) PRIMARY KEY,
    event_id VARCHAR(255) REFERENCES events(id) ON DELETE CASCADE,
    code VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(255),
    description TEXT,
    discount_type VARCHAR(50),
    discount_value DOUBLE PRECISION,
    max_discount_amount DOUBLE PRECISION,
    min_order_amount DOUBLE PRECISION,
    usage_limit INT,
    used_count INT,
    start_at TIMESTAMP,
    end_at TIMESTAMP,
    status VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

INSERT INTO event_categories (id, name, description, status, created_at, updated_at)
VALUES
('cat-001', 'Âm Nhạc & Concert', 'Hòa nhạc, live show âm nhạc kịch tính, lễ hội âm nhạc đỉnh cao.', 'ACTIVE', NOW(), NOW()),
('cat-002', 'Thể Thao & Giải Đấu', 'Giải chạy marathon, bóng đá, esports và sự kiện thể thao sôi động.', 'ACTIVE', NOW(), NOW()),
('cat-003', 'Sân Khấu & Nghệ Thuật', 'Kịch nói, nhạc kịch, hài kịch và trình diễn nghệ thuật.', 'ACTIVE', NOW(), NOW()),
('cat-004', 'Hội Thảo & Công Nghệ', 'Tech summit, hội thảo AI, khởi nghiệp và triển lãm công nghệ.', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO event_organizers (id, user_id, description, role, status, created_at, updated_at)
VALUES
('org-001', '22222222-2222-2222-2222-222222222222', 'Halo Entertainment Inc. - Đơn vị tổ chức sự kiện âm nhạc và văn hóa hàng đầu.', 'OWNER', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO events (id, title, description, banner_url, thumbnail_url, location_name, address, event_status, event_approval_status, start_time, end_time, sale_start_time, sale_end_time, category_id, created_at, updated_at)
VALUES
('event-001', 'Đêm Nhạc Halo Concert 2026: Vũ Điệu Âm Thanh', 'Đêm nhạc bùng nổ quy tụ những ca sĩ hàng đầu Việt Nam.', 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=1200', 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=400', 'Sân vận động Quân khu 7', '202 Hoàng Văn Thụ, Phường 9, Phú Nhuận, TP.HCM', 'LIVE', 'APPROVED', '2026-10-15 19:00:00', '2026-10-15 22:30:00', '2026-08-01 09:00:00', '2026-10-14 23:59:59', 'cat-001', NOW(), NOW()),
('event-002', 'Giải Chạy Halo Marathon TP.HCM 2026', 'Giải chạy việt dã thường niên thử thách bản thân.', 'https://images.unsplash.com/photo-1452626038306-9aae5e071dd3?w=1200', 'https://images.unsplash.com/photo-1452626038306-9aae5e071dd3?w=400', 'Công viên Văn hóa Đầm Sen', '3 Hòa Bình, Phường 3, Quận 11, TP.HCM', 'LIVE', 'APPROVED', '2026-11-20 05:00:00', '2026-11-20 11:00:00', '2026-08-10 09:00:00', '2026-11-19 23:59:59', 'cat-002', NOW(), NOW()),
('event-003', 'Hội Thảo Công Nghệ Halo AI Summit 2026', 'Hội thảo công nghệ AI & Cloud Microservices quy tụ các chuyên gia phần mềm.', 'https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=1200', 'https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=400', 'GEM Center', '8 Nguyễn Bỉnh Khiêm, Đa Kao, Quận 1, TP.HCM', 'LIVE', 'APPROVED', '2026-12-05 08:30:00', '2026-12-05 17:00:00', '2026-08-15 09:00:00', '2026-12-04 23:59:59', 'cat-004', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO event_organizer_mapping (event_id, organizer_id)
VALUES
('event-001', 'org-001'),
('event-002', 'org-001'),
('event-003', 'org-001')
ON CONFLICT DO NOTHING;

INSERT INTO staff_assignments (id, event_id, user_id, status, assigned_at)
VALUES
('staff-assign-01', 'event-001', '33333333-3333-3333-3333-333333333333', 'ACTIVE', NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO vouchers (id, event_id, code, name, description, discount_type, discount_value, max_discount_amount, min_order_amount, usage_limit, used_count, start_at, end_at, status, created_at, updated_at)
VALUES
('voucher-01', 'event-001', 'HALOVIP2026', 'Ưu đãi 10% vé VIP', 'Giảm 10% cho vé VIP, tối đa 200.000 VNĐ.', 'PERCENTAGE', 10.0, 200000.0, 500000.0, 100, 5, '2026-08-01 00:00:00', '2026-10-14 23:59:59', 'ACTIVE', NOW(), NOW()),
('voucher-02', 'event-001', 'HALO50K', 'Giảm 50k đơn từ 300k', 'Giảm 50.000 VNĐ cho đơn từ 300.000 VNĐ.', 'FIXED_AMOUNT', 50000.0, NULL, 300000.0, 50, 2, '2026-08-01 00:00:00', '2026-10-14 23:59:59', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- 3. TICKET DB (ticket_db)
-- ============================================================================
\c ticket_db;

CREATE TABLE IF NOT EXISTS ticket_types (
    id VARCHAR(255) PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    total_quantity INT,
    ticket_type_status VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS seat_layouts (
    id VARCHAR(255) PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    svg_data TEXT,
    status VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS seat_zones (
    id VARCHAR(255) PRIMARY KEY,
    seat_layout_id VARCHAR(255) REFERENCES seat_layouts(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    color_code VARCHAR(50),
    capacity INT
);

CREATE TABLE IF NOT EXISTS seat_rows (
    id VARCHAR(255) PRIMARY KEY,
    seat_zone_id VARCHAR(255) REFERENCES seat_zones(id) ON DELETE CASCADE,
    label VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS seats (
    id VARCHAR(255) PRIMARY KEY,
    seat_row_id VARCHAR(255) REFERENCES seat_rows(id) ON DELETE CASCADE,
    seat_number VARCHAR(255) NOT NULL,
    x_coordinate DOUBLE PRECISION,
    y_coordinate DOUBLE PRECISION,
    seat_type VARCHAR(50),
    seat_status VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS tickets (
    id VARCHAR(255) PRIMARY KEY,
    ticket_type_id VARCHAR(255) REFERENCES ticket_types(id),
    seat_id VARCHAR(255) UNIQUE REFERENCES seats(id),
    event_id VARCHAR(255) NOT NULL,
    booking_id VARCHAR(255),
    owner_id VARCHAR(255) NOT NULL,
    qr_code_hash VARCHAR(255) UNIQUE,
    qr_version INT,
    status VARCHAR(50),
    created_at TIMESTAMP,
    issued_at TIMESTAMP,
    used_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS checkins (
    id VARCHAR(255) PRIMARY KEY,
    ticket_id VARCHAR(255) UNIQUE REFERENCES tickets(id),
    event_id VARCHAR(255) NOT NULL,
    staff_assignment_id VARCHAR(255),
    status VARCHAR(50),
    checked_in_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS checkin_attempts (
    id VARCHAR(255) PRIMARY KEY,
    ticket_id VARCHAR(255) REFERENCES tickets(id),
    staff_assignment_id VARCHAR(255),
    device_info VARCHAR(255),
    result VARCHAR(50),
    failure_reason VARCHAR(255),
    attempted_at TIMESTAMP
);

INSERT INTO ticket_types (id, event_id, name, price, total_quantity, ticket_type_status, created_at, updated_at)
VALUES
('tt-001', 'event-001', 'Vé VIP Fan Zone', 1500000.0, 100, 'ACTIVE', NOW(), NOW()),
('tt-002', 'event-001', 'Vé Khán Đài A (Standard)', 800000.0, 200, 'ACTIVE', NOW(), NOW()),
('tt-003', 'event-001', 'Vé Khán Đài B (Economy)', 500000.0, 300, 'ACTIVE', NOW(), NOW()),
('tt-004', 'event-002', 'Vé Tham Gia Marathon 21KM', 450000.0, 500, 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO seat_layouts (id, event_id, svg_data, status, created_at, updated_at)
VALUES
('layout-001', 'event-001', '<svg viewBox="0 0 800 600"><rect width="800" height="100" fill="#333"/><text x="350" y="60" fill="#fff">SÂN KHẤU CHÍNH</text></svg>', 'PUBLISHED', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO seat_zones (id, seat_layout_id, name, color_code, capacity)
VALUES
('zone-vip', 'layout-001', 'Khu VIP (Sát sân khấu)', '#FFD700', 20),
('zone-std', 'layout-001', 'Khu Khán Đài A', '#1E90FF', 40)
ON CONFLICT (id) DO NOTHING;

INSERT INTO seat_rows (id, seat_zone_id, label)
VALUES
('row-a', 'zone-vip', 'Hàng A'),
('row-b', 'zone-vip', 'Hàng B'),
('row-c', 'zone-std', 'Hàng C')
ON CONFLICT (id) DO NOTHING;

INSERT INTO seats (id, seat_row_id, seat_number, x_coordinate, y_coordinate, seat_type, seat_status)
VALUES
('seat-a1', 'row-a', 'A1', 100.0, 200.0, 'VIP', 'SOLD'),
('seat-a2', 'row-a', 'A2', 140.0, 200.0, 'VIP', 'SOLD'),
('seat-a3', 'row-a', 'A3', 180.0, 200.0, 'VIP', 'AVAILABLE'),
('seat-c1', 'row-c', 'C1', 100.0, 350.0, 'REGULAR', 'AVAILABLE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO tickets (id, ticket_type_id, seat_id, event_id, booking_id, owner_id, qr_code_hash, qr_version, status, created_at, issued_at, used_at)
VALUES
('ticket-001', 'tt-001', 'seat-a1', 'event-001', 'booking-001', '44444444-4444-4444-4444-444444444444', 'QR_HALO_001_VIP_A1', 1, 'VALID', NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days', NULL),
('ticket-002', 'tt-001', 'seat-a2', 'event-001', 'booking-001', '44444444-4444-4444-4444-444444444444', 'QR_HALO_002_VIP_A2', 1, 'USED', NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days', NOW() - INTERVAL '1 hour')
ON CONFLICT (id) DO NOTHING;

INSERT INTO checkins (id, ticket_id, event_id, staff_assignment_id, status, checked_in_at)
VALUES
('checkin-001', 'ticket-002', 'event-001', 'staff-assign-01', 'VALID', NOW() - INTERVAL '1 hour')
ON CONFLICT (id) DO NOTHING;

INSERT INTO checkin_attempts (id, ticket_id, staff_assignment_id, device_info, result, failure_reason, attempted_at)
VALUES
('attempt-001', 'ticket-002', 'staff-assign-01', 'Mobile Scanner App 01 - Gate A', 'SUCCESS', NULL, NOW() - INTERVAL '1 hour')
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- 4. BOOKING DB (booking_db)
-- ============================================================================
\c booking_db;

CREATE TABLE IF NOT EXISTS bookings (
    id VARCHAR(255) PRIMARY KEY,
    booking_code VARCHAR(255) UNIQUE NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    voucher_id VARCHAR(255),
    subtotal DOUBLE PRECISION,
    discount_amount DOUBLE PRECISION,
    total_amount DOUBLE PRECISION,
    status VARCHAR(50),
    expired_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS booking_items (
    id VARCHAR(255) PRIMARY KEY,
    booking_id VARCHAR(255) REFERENCES bookings(id) ON DELETE CASCADE,
    ticket_type_id VARCHAR(255) NOT NULL,
    quantity INT,
    unit_price DOUBLE PRECISION,
    sub_total DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS seat_reservations (
    id VARCHAR(255) PRIMARY KEY,
    booking_id VARCHAR(255) REFERENCES bookings(id) ON DELETE CASCADE,
    seat_id VARCHAR(255) NOT NULL,
    status VARCHAR(50),
    lock_expires_at TIMESTAMP,
    created_at TIMESTAMP
);

INSERT INTO bookings (id, booking_code, customer_id, event_id, voucher_id, subtotal, discount_amount, total_amount, status, expired_at, created_at, updated_at)
VALUES
('booking-001', 'HT-20260829-001', '44444444-4444-4444-4444-444444444444', 'event-001', 'voucher-01', 3000000.0, 200000.0, 2800000.0, 'PAID', NOW() + INTERVAL '30 days', NOW(), NOW()),
('booking-002', 'HT-20260829-002', '44444444-4444-4444-4444-444444444444', 'event-001', NULL, 800000.0, 0.0, 800000.0, 'PENDING', NOW() + INTERVAL '10 minutes', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO booking_items (id, booking_id, ticket_type_id, quantity, unit_price, sub_total)
VALUES
('bitem-001', 'booking-001', 'tt-001', 2, 1500000.0, 3000000.0),
('bitem-002', 'booking-002', 'tt-002', 1, 800000.0, 800000.0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO seat_reservations (id, booking_id, seat_id, status, lock_expires_at, created_at)
VALUES
('res-001', 'booking-001', 'seat-a1', 'CONFIRMED', NOW() + INTERVAL '1 hour', NOW()),
('res-002', 'booking-001', 'seat-a2', 'CONFIRMED', NOW() + INTERVAL '1 hour', NOW()),
('res-003', 'booking-002', 'seat-c1', 'HOLDING', NOW() + INTERVAL '10 minutes', NOW())
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- 5. PAYMENT DB (payment_db)
-- ============================================================================
\c payment_db;

CREATE TABLE IF NOT EXISTS payments (
    id VARCHAR(255) PRIMARY KEY,
    booking_id VARCHAR(255) NOT NULL,
    amount DOUBLE PRECISION,
    method VARCHAR(50),
    provider VARCHAR(50),
    status VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payment_transactions (
    id VARCHAR(255) PRIMARY KEY,
    payment_id VARCHAR(255) REFERENCES payments(id) ON DELETE CASCADE,
    gateway_ref_id VARCHAR(255),
    payload_json TEXT,
    status VARCHAR(50),
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS refunds (
    id VARCHAR(255) PRIMARY KEY,
    payment_id VARCHAR(255) REFERENCES payments(id) ON DELETE CASCADE,
    amount DOUBLE PRECISION,
    reason VARCHAR(255),
    status VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

INSERT INTO payments (id, booking_id, amount, method, provider, status, created_at, updated_at)
VALUES
('payment-001', 'booking-001', 2800000.0, 'VNPAY', 'VNPAY_GATEWAY', 'SUCCESS', NOW(), NOW()),
('payment-002', 'booking-002', 800000.0, 'ZALOPAY', 'ZALOPAY_GATEWAY', 'PENDING', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO payment_transactions (id, payment_id, gateway_ref_id, payload_json, status, created_at)
VALUES
('tx-001', 'payment-001', 'VNPAY_REF_20260829001', '{"vnp_ResponseCode":"00","vnp_TransactionNo":"14589230","vnp_BankCode":"NCB"}', 'SUCCESS', NOW())
ON CONFLICT (id) DO NOTHING;
