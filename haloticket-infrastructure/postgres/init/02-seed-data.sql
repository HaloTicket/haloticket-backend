-- SQL Seed Data Script for HaloTicket Microservices Platform
-- Creates tables (if not exist) and inserts initial sample data matching frontend mock data for each service DB
-- Run with psql -f (or docker cp + psql -f), never a Windows text pipeline.
\set ON_ERROR_STOP on
\encoding UTF8

-- 1. USER DB (user_db)
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
('11111111-1111-1111-1111-111111111111', 'e0000000-0000-0000-0000-000000000001', 'admin@haloticket.vn', 'Trần Long Vũ (Admin)', '0901234567', 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('22222222-2222-2222-2222-222222222222', 'e0000000-0000-0000-0000-000000000002', 'organizer@haloticket.vn', 'Nhà Hát Kịch IDECAF', '0912345678', 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('33333333-3333-3333-3333-333333333333', 'e0000000-0000-0000-0000-000000000005', 'staff@haloticket.vn', 'Nguyễn Văn Soát Vé', '0987654321', 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('44444444-4444-4444-4444-444444444444', 'e0000000-0000-0000-0000-000000000004', 'customer@haloticket.vn', 'Lê Thị Mua Vé', '0933445566', 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200', true, 'FEMALE', 'ACTIVE', NOW(), NOW()),
('55555555-5555-5555-5555-555555555555', 'e0000000-0000-0000-0000-000000000006', 'hoangdung@haloticket.vn', 'Hoàng Dũng Entertainment', '0977889900', 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('66666666-6666-6666-6666-666666666666', 'e0000000-0000-0000-0000-000000000007', 'seller1@haloticket.vn', 'Nguyễn Văn Nhượng Vé', '0911223344', 'https://images.unsplash.com/photo-1522075469751-3a6694fb2f61?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_roles (user_id, role)
VALUES 
('11111111-1111-1111-1111-111111111111', 'ADMIN'),
('22222222-2222-2222-2222-222222222222', 'ORGANIZER'),
('33333333-3333-3333-3333-333333333333', 'STAFF'),
('44444444-4444-4444-4444-444444444444', 'CUSTOMER'),
('55555555-5555-5555-5555-555555555555', 'ORGANIZER'),
('66666666-6666-6666-6666-666666666666', 'CUSTOMER')
ON CONFLICT DO NOTHING;

INSERT INTO ekyc_profiles (id, user_id, document_type, document_number, front_image_url, back_image_url, bank_name, bank_account_name, bank_account_number, status, approved_at, created_at, updated_at)
VALUES
('ekyc-org-001', '22222222-2222-2222-2222-222222222222', 'BUSINESS_LICENSE', '0312345678', 'https://example.com/ekyc/license_front.jpg', 'https://example.com/ekyc/license_back.jpg', 'MBBank', 'NHA HAT KICH IDECAF', '999988887777', 'APPROVED', NOW() - INTERVAL '30 days', NOW(), NOW()),
('ekyc-cust-001', '44444444-4444-4444-4444-444444444444', 'ID_CARD', '079200001234', 'https://example.com/ekyc/cccd_front.jpg', 'https://example.com/ekyc/cccd_back.jpg', 'Vietcombank', 'LE THI MUA VE', '1012345678', 'APPROVED', NOW() - INTERVAL '15 days', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 2. EVENT DB (event_db)
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
    name VARCHAR(255),
    avatar_url VARCHAR(255),
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
    banner_url TEXT,
    thumbnail_url TEXT,
    location_type VARCHAR(50),
    location_name TEXT,
    city VARCHAR(255),
    ward VARCHAR(255),
    address TEXT,
    dates_summary VARCHAR(255),
    extra_days_count INT,
    min_price DOUBLE PRECISION,
    event_status VARCHAR(50),
    event_approval_status VARCHAR(50),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    sale_start_time TIMESTAMP,
    sale_end_time TIMESTAMP,
    category_id VARCHAR(255) REFERENCES event_categories(id),
    is_private BOOLEAN DEFAULT false,
    confirmation_message TEXT,
    enable_custom_questions BOOLEAN DEFAULT false,
    question_scope VARCHAR(50),
    tickets_data TEXT,
    custom_questions_data TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS event_gallery (
    event_id VARCHAR(255) REFERENCES events(id) ON DELETE CASCADE,
    image_url TEXT
);

CREATE TABLE IF NOT EXISTS event_organizer_mapping (
    event_id VARCHAR(255) REFERENCES events(id) ON DELETE CASCADE,
    organizer_id VARCHAR(255) REFERENCES event_organizers(id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, organizer_id)
);

CREATE TABLE IF NOT EXISTS search_logs (
    id VARCHAR(255) PRIMARY KEY,
    query_term VARCHAR(255) UNIQUE NOT NULL,
    search_count BIGINT DEFAULT 1,
    created_at TIMESTAMP
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
('cat-001', 'Nhạc sống', 'Hòa nhạc, live show âm nhạc kịch tính, lễ hội âm nhạc đỉnh cao.', 'ACTIVE', NOW(), NOW()),
('cat-002', 'Thể Thao', 'Giải chạy marathon, bóng đá, esports và sự kiện thể thao sôi động.', 'ACTIVE', NOW(), NOW()),
('cat-003', 'Sân khấu & Nghệ thuật', 'Kịch nói, nhạc kịch, hài kịch và trình diễn nghệ thuật.', 'ACTIVE', NOW(), NOW()),
('cat-004', 'Hội thảo & Workshop', 'Tech summit, hội thảo AI, làm gốm, nghệ thuật & sáng tạo.', 'ACTIVE', NOW(), NOW()),
('cat-005', 'Tham quan & Trải nghiệm', 'Tour du lịch, triển lãm đa giác quan và trải nghiệm văn hóa.', 'ACTIVE', NOW(), NOW()),
('cat-006', 'Khác', 'Các sự kiện giải trí và văn hóa đa dạng khác.', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO event_organizers (id, user_id, name, avatar_url, description, role, status, created_at, updated_at)
VALUES
('org-001', '22222222-2222-2222-2222-222222222222', 'Nhà Hát Kịch IDECAF', 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200&q=80', 'Nhà Hát Kịch IDECAF - Sân khấu kịch nổi tiếng hàng đầu tại TP.HCM.', 'OWNER', 'ACTIVE', NOW(), NOW()),
('org-002', '55555555-5555-5555-5555-555555555555', 'Hoàng Dũng Entertainment', 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=200&q=80', 'Hoàng Dũng Entertainment - Đơn vị sản xuất concert ca nhạc chuyên nghiệp.', 'OWNER', 'ACTIVE', NOW(), NOW()),
('org-003', '11111111-1111-1111-1111-111111111111', 'Halo Entertainment Inc.', 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=200&q=80', 'Halo Entertainment Inc. - Ban tổ chức sự kiện văn hóa lớn.', 'OWNER', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO events (id, title, description, banner_url, thumbnail_url, location_type, location_name, city, ward, address, dates_summary, extra_days_count, min_price, event_status, event_approval_status, start_time, end_time, sale_start_time, sale_end_time, category_id, is_private, confirmation_message, enable_custom_questions, question_scope, created_at, updated_at)
VALUES
('nxnx37-hoc-vien-phep-thuat', 'Nhà Hát Kịch IDECAF: NXNX37 - Học Viện Phép Thuật', 'Chương trình Ngày Xửa Ngày Xưa 37 [NXNX37] -- HỌC VIỆN PHÉP THUẬT --\n\nVở kịch thiếu nhi hoành tráng được đầu tư công phu với bối cảnh Học Viện Phép Thuật huyền bí.', 'https://images.unsplash.com/photo-1514306191717-452ec28c7814?w=1000&q=80', 'https://images.unsplash.com/photo-1514306191717-452ec28c7814?w=600&q=80', 'offline', 'Nhà Hát Bến Thành', 'TP. Hồ Chí Minh', 'Phường Bến Nghé', 'Số 06 Mạc Đĩnh Chi, Phường Bến Nghé, Quận 1, TP.HCM', '20:00, 25 Tháng 09, 2026 - 23:00, 27 Tháng 09, 2026', 5, 250000.0, 'LIVE', 'APPROVED', '2026-09-25 20:00:00', '2026-09-27 23:00:00', '2026-08-01 09:00:00', '2026-09-27 20:00:00', 'cat-003', false, 'Cảm ơn bạn đã đặt vé tham dự Ngày Xửa Ngày Xưa 37!', true, 'ticket', NOW(), NOW()),

('xoay-tron-concert-hoang-dung', 'Xoay Tròn Concert - Hoàng Dũng', 'Live concert cá nhân đặc sắc từ ca sĩ Hoàng Dũng mang tên Xoay Tròn.', 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=1000&q=80', 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=600&q=80', 'offline', 'VINHOMES OCEAN PARK 3', 'Hưng Yên', 'Xã Văn Giang', 'Vinhomes Ocean Park 3, Xã Văn Giang, Tỉnh Hưng Yên', '19:00, 06 Tháng 09, 2026', 0, 1399000.0, 'LIVE', 'APPROVED', '2026-09-06 19:00:00', '2026-09-06 22:30:00', '2026-07-15 09:00:00', '2026-09-06 19:00:00', 'cat-001', false, 'Hẹn gặp bạn tại Xoay Tròn Concert!', false, 'order', NOW(), NOW()),

('idecaf-tam-cam-dai-chien', 'Nhà Hát Kịch IDECAF: TẤM CÁM ĐẠI CHIẾN!', 'Vở kịch hài kước sắc sảo Tấm Cám Đại Chiến biến tấu hiện đại.', 'https://images.unsplash.com/photo-1507676184212-d03ab07a01bf?w=800&q=80', 'https://images.unsplash.com/photo-1507676184212-d03ab07a01bf?w=600&q=80', 'offline', 'Nhà Hát Bến Thành', 'TP. Hồ Chí Minh', 'Phường Bến Nghé', 'Số 06 Mạc Đĩnh Chi, Phường Bến Nghé, Quận 1, TP.HCM', '19:00, 03 Tháng 10, 2026', 2, 220000.0, 'LIVE', 'APPROVED', '2026-10-03 19:00:00', '2026-10-03 22:00:00', '2026-08-01 09:00:00', '2026-10-03 19:00:00', 'cat-003', false, 'Cảm ơn bạn đã mua vé xem kịch Tấm Cám Đại Chiến!', false, 'order', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO search_logs (id, query_term, search_count, created_at)
VALUES
('log-001', 'idecaf', 1520, NOW()),
('log-002', 'xoay tròn', 980, NOW()),
('log-003', 'Pickleball World Cup', 750, NOW())
ON CONFLICT (id) DO NOTHING;

-- Seed Cast for NXNX37 & Events
INSERT INTO event_cast (event_id, actor_name)
SELECT seed.event_id, seed.actor_name FROM (VALUES
('nxnx37-hoc-vien-phep-thuat', 'Bạch Long'),
('nxnx37-hoc-vien-phep-thuat', 'Thanh Thuỷ'),
('xoay-tron-concert-hoang-dung', 'Hoàng Dũng')
) AS seed(event_id, actor_name)
WHERE NOT EXISTS (
    SELECT 1 FROM event_cast existing
    WHERE existing.event_id = seed.event_id AND existing.actor_name = seed.actor_name
);
-- Seed Gallery Images
INSERT INTO event_gallery (event_id, image_url)
SELECT seed.event_id, seed.image_url FROM (VALUES
('nxnx37-hoc-vien-phep-thuat', 'https://images.unsplash.com/photo-1465847899084-d164df4dedc6?w=800&q=80')
) AS seed(event_id, image_url)
WHERE NOT EXISTS (
    SELECT 1 FROM event_gallery existing
    WHERE existing.event_id = seed.event_id AND existing.image_url = seed.image_url
);

INSERT INTO event_organizer_mapping (event_id, organizer_id)
VALUES
('nxnx37-hoc-vien-phep-thuat', 'org-001'),
('xoay-tron-concert-hoang-dung', 'org-002'),
('idecaf-tam-cam-dai-chien', 'org-001')
ON CONFLICT DO NOTHING;

-- 3. TICKET DB (ticket_db)
\c ticket_db;

CREATE TABLE IF NOT EXISTS ticket_types (
    id VARCHAR(255) PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    is_free BOOLEAN DEFAULT false,
    total_quantity INT,
    min_per_order INT DEFAULT 1,
    max_per_order INT DEFAULT 10,
    start_sale_date TIMESTAMP,
    end_sale_date TIMESTAMP,
    description TEXT,
    ticket_type_status VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    version BIGINT DEFAULT 0
);

INSERT INTO ticket_types (id, event_id, name, price, is_free, total_quantity, min_per_order, max_per_order, description, ticket_type_status, created_at, updated_at, version)
VALUES
('tt-nxnx-vvip', 'nxnx37-hoc-vien-phep-thuat', 'VVIP', 400000.0, false, 100, 1, 10, 'Hạng vé VVIP vị trí đẹp nhất sát sân khấu', 'ACTIVE', NOW(), NOW(), 0),
('tt-nxnx-vip', 'nxnx37-hoc-vien-phep-thuat', 'VIP', 250000.0, false, 200, 1, 10, 'Hạng vé VIP khu vực khán đài trung tâm', 'ACTIVE', NOW(), NOW(), 0),
('tt-xoay-a2', 'xoay-tron-concert-hoang-dung', 'XOAY TRÒN A2 (SEATED)', 2499000.0, false, 80, 1, 4, 'Ghế ngồi chính diện sân khấu concert Hoàng Dũng', 'ACTIVE', NOW(), NOW(), 0),
('tt-xoay-ga', 'xoay-tron-concert-hoang-dung', 'XOAY TRÒN GA (STANDING)', 1399000.0, false, 500, 1, 6, 'Khu vực đứng gần sân khấu sôi động', 'ACTIVE', NOW(), NOW(), 0),
('tt-tc-std', 'idecaf-tam-cam-dai-chien', 'Khán Đài Thường', 220000.0, false, 300, 1, 10, 'Vé thường xem Tấm Cám Đại Chiến', 'ACTIVE', NOW(), NOW(), 0)
ON CONFLICT (id) DO NOTHING;

-- 4. PAYMENT DB (payment_db)
\c payment_db;

CREATE TABLE IF NOT EXISTS organizer_payout_accounts (
    id VARCHAR(255) PRIMARY KEY,
    organizer_user_id VARCHAR(255) NOT NULL,
    account_holder VARCHAR(255) NOT NULL,
    account_number VARCHAR(255) NOT NULL,
    bank_name VARCHAR(255) NOT NULL,
    bank_branch VARCHAR(255),
    business_type VARCHAR(50),
    tax_name VARCHAR(255),
    tax_code VARCHAR(255),
    tax_address TEXT,
    is_verified BOOLEAN DEFAULT true,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

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

-- 5. MARKETPLACE DB (marketplace_db)
\c marketplace_db;

CREATE TABLE IF NOT EXISTS resale_listings (
    id VARCHAR(255) PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    seller_id VARCHAR(255) NOT NULL,
    resale_code VARCHAR(255) UNIQUE NOT NULL,
    is_retail BOOLEAN NOT NULL DEFAULT true,
    quantity INT NOT NULL,
    showtime_str VARCHAR(255),
    tier_name VARCHAR(255) NOT NULL,
    area_name VARCHAR(255),
    tier_color VARCHAR(50),
    price_per_ticket DOUBLE PRECISION NOT NULL,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

INSERT INTO resale_listings (id, event_id, seller_id, resale_code, is_retail, quantity, showtime_str, tier_name, area_name, tier_color, price_per_ticket, status, created_at, updated_at)
VALUES
('rsl-1', 'xoay-tron-concert-hoang-dung', '66666666-6666-6666-6666-666666666666', 'RSL461904575', true, 2, '19:00, 06 tháng 09, 2026', 'XOAY TRÒN A2 (SEATED)', 'XOAY-TRON-A2', '#06B6D4', 2499000.0, 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
