
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
('11111111-1111-1111-1111-111111111111', 'kc-admin-001', 'admin@haloticket.vn', 'Trần Long Vũ (Admin)', '0901234567', 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('22222222-2222-2222-2222-222222222222', 'kc-organizer-001', 'organizer@haloticket.vn', 'Nhà Hát Kịch IDECAF', '0912345678', 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('33333333-3333-3333-3333-333333333333', 'kc-staff-001', 'staff@haloticket.vn', 'Nguyễn Văn Soát Vé', '0987654321', 'https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('44444444-4444-4444-4444-444444444444', 'kc-customer-001', 'customer@haloticket.vn', 'Lê Thị Mua Vé', '0933445566', 'https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200', true, 'FEMALE', 'ACTIVE', NOW(), NOW()),
('55555555-5555-5555-5555-555555555555', 'kc-organizer-002', 'hoangdung@haloticket.vn', 'Hoàng Dũng Entertainment', '0977889900', 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW()),
('66666666-6666-6666-6666-666666666666', 'kc-seller-001', 'seller1@haloticket.vn', 'Nguyễn Văn Nhượng Vé', '0911223344', 'https://images.unsplash.com/photo-1522075469751-3a6694fb2f61?w=200', true, 'MALE', 'ACTIVE', NOW(), NOW())
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
    banner_url VARCHAR(255),
    thumbnail_url VARCHAR(255),
    location_name VARCHAR(255),
    address VARCHAR(255),
    author VARCHAR(255),
    director VARCHAR(255),
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
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS event_cast (
    event_id VARCHAR(255) REFERENCES events(id) ON DELETE CASCADE,
    actor_name VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS event_gallery (
    event_id VARCHAR(255) REFERENCES events(id) ON DELETE CASCADE,
    image_url VARCHAR(255)
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

-- Insert Comprehensive Real-world Events
INSERT INTO events (id, title, description, banner_url, thumbnail_url, location_name, address, author, director, dates_summary, extra_days_count, min_price, event_status, event_approval_status, start_time, end_time, sale_start_time, sale_end_time, category_id, created_at, updated_at)
VALUES
('nxnx37-hoc-vien-phep-thuat', 'Nhà Hát Kịch IDECAF: NXNX37 - Học Viện Phép Thuật', 'Chương trình Ngày Xửa Ngày Xưa 37 [NXNX37] -- HỌC VIỆN PHÉP THUẬT --\n\nVở kịch thiếu nhi hoành tráng được đầu tư công phu với bối cảnh Học Viện Phép Thuật huyền bí. Những cuộc phiêu lưu nghẹt thở, các phép thuật thần kỳ cùng dàn diễn viên gạo cội sẽ mang tới cho quý khán giả những giây phút thư giãn tuyệt vời nhất.', 'https://images.unsplash.com/photo-1514306191717-452ec28c7814?w=1000&q=80', 'https://images.unsplash.com/photo-1514306191717-452ec28c7814?w=600&q=80', 'Nhà Hát Bến Thành', 'Số 06 Mạc Đĩnh Chi, Phường Sài Gòn, Thành phố Hồ Chí Minh', 'Quang Thảo', 'Đình Toàn', '20:00, 25 Tháng 09, 2026 - 23:00, 27 Tháng 09, 2026', 5, 250000.0, 'LIVE', 'APPROVED', '2026-09-25 20:00:00', '2026-09-27 23:00:00', '2026-08-01 09:00:00', '2026-09-27 20:00:00', 'cat-003', NOW(), NOW()),

('xoay-tron-concert-hoang-dung', 'Xoay Tròn Concert - Hoàng Dũng', 'Live concert cá nhân đặc sắc từ ca sĩ Hoàng Dũng mang tên Xoay Tròn với những bản phối acoustic ngọt ngào và dàn nhạc giao hưởng hoành tráng.', 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=1000&q=80', 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=600&q=80', 'VINHOMES OCEAN PARK 3', 'Vinhomes Ocean Park 3, Xã Văn Giang, Tỉnh Hưng Yên', 'Hoàng Dũng', 'Hoàng Dũng Team', '19:00, 06 Tháng 09, 2026', 0, 1399000.0, 'LIVE', 'APPROVED', '2026-09-06 19:00:00', '2026-09-06 22:30:00', '2026-07-15 09:00:00', '2026-09-06 19:00:00', 'cat-001', NOW(), NOW()),

('idecaf-tam-cam-dai-chien', 'Nhà Hát Kịch IDECAF: TẤM CÁM ĐẠI CHIẾN!', 'Vở kịch hài kước sắc sảo Tấm Cám Đại Chiến biến tấu hiện đại, đầy bất ngờ thú vị từ dàn nghệ sĩ IDECAF.', 'https://images.unsplash.com/photo-1507676184212-d03ab07a01bf?w=800&q=80', 'https://images.unsplash.com/photo-1507676184212-d03ab07a01bf?w=600&q=80', 'Nhà Hát Bến Thành', 'Số 06 Mạc Đĩnh Chi, Phường Sài Gòn, TP.HCM', 'Hùng Lâm', 'Đình Toàn', '19:00, 03 Tháng 10, 2026', 2, 220000.0, 'LIVE', 'APPROVED', '2026-10-03 19:00:00', '2026-10-03 22:00:00', '2026-08-01 09:00:00', '2026-10-03 19:00:00', 'cat-003', NOW(), NOW()),

('phung-khanh-linh-tour', 'GIỮA MỘT VẠN TOUR (MỞ RỘNG) - PHÙNG KHÁNH LINH', 'Concert tour mở rộng đậm chất Pop / Synth-wave đột phá từ nữ ca sĩ Phùng Khánh Linh.', 'https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=800&q=80', 'https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=600&q=80', 'Nhà Thi Đấu Nguyễn Du', '116 Nguyễn Du, Phường Bến Thành, Quận 1, TP.HCM', 'Phùng Khánh Linh', 'Hãng Đĩa Thời Đại', '19:30, 17 Tháng 10, 2026', 0, 600000.0, 'LIVE', 'APPROVED', '2026-10-17 19:30:00', '2026-10-17 22:30:00', '2026-08-10 09:00:00', '2026-10-17 19:30:00', 'cat-001', NOW(), NOW()),

('mat-nham-mat-mo-concert', 'MẮT NHẮM MẮT MỞ CONCERT - Vũ & Friends', 'Live concert mộng mơ lãng mạn quy tụ Vũ. và những người bạn nghệ sĩ Indie được yêu thích nhất.', 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=800&q=80', 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=600&q=80', 'Nhà Thi Đấu Quân Khu 7', '202 Hoàng Văn Thụ, Phường 9, Phú Nhuận, TP.HCM', 'Vũ.', 'Vũ. Production', '19:00, 24 Tháng 10, 2026', 0, 800000.0, 'LIVE', 'APPROVED', '2026-10-24 19:00:00', '2026-10-24 22:30:00', '2026-08-15 09:00:00', '2026-10-24 19:00:00', 'cat-001', NOW(), NOW()),

('dalat-cloud-concert', 'MÂY TRÂN TRỌNG MÂY LANG THANG - LẮNG NGHE MÂY HÁT ĐÀ LẠT', 'Đêm nhạc acoustic giữa núi rừng Đà Lạt thơ mộng tại Mây Lang Thang.', 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800&q=80', 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&q=80', 'Sân Khấu Mây Lang Thang', '7B Hẻm Hoàng Hoa Thám, Phường 10, Thành phố Đà Lạt', 'Mây Lang Thang', 'Mây Studio', '17:00, 15 Tháng 11, 2026', 0, 500000.0, 'LIVE', 'APPROVED', '2026-11-15 17:00:00', '2026-11-15 20:00:00', '2026-09-01 09:00:00', '2026-11-15 17:00:00', 'cat-001', NOW(), NOW()),

('spt-4', 'ĐẤU TRƯỜNG HỖN CHIẾN MÙA 3 - VIETNAM ESPORTS CHAMPIONSHIP', 'Giải đấu Esports chuyên nghiệp tranh tài gay cấn giữa các đội tuyển hàng đầu Việt Nam.', 'https://images.unsplash.com/photo-1542751371-adc38448a05e?w=800&q=80', 'https://images.unsplash.com/photo-1542751371-adc38448a05e?w=600&q=80', 'Nhà Thi Đấu Phú Thọ', '219 Lý Thường Kiệt, Phường 15, Quận 11, TP.HCM', 'VETV', 'Esports Vietnam', '13:00, 12 Tháng 09, 2026', 1, 150000.0, 'LIVE', 'APPROVED', '2026-09-12 13:00:00', '2026-09-12 21:00:00', '2026-08-01 09:00:00', '2026-09-12 13:00:00', 'cat-002', NOW(), NOW()),

('event-002', 'Giải Chạy Halo Marathon TP.HCM 2026 - Chinh Phục Cung Đường Di Sản', 'Giải chạy marathon việt dã hàng ngàn vận động viên tham gia quy mô lớn nhất TP.HCM.', 'https://images.unsplash.com/photo-1452626038306-9aae5e071dd3?w=800&q=80', 'https://images.unsplash.com/photo-1452626038306-9aae5e071dd3?w=600&q=80', 'Công viên Văn hóa Đầm Sen', '3 Hòa Bình, Phường 3, Quận 11, TP.HCM', 'Halo Sports', 'BTC Marathon', '05:00, 20 Tháng 11, 2026', 0, 450000.0, 'LIVE', 'APPROVED', '2026-11-20 05:00:00', '2026-11-20 11:00:00', '2026-08-10 09:00:00', '2026-11-19 23:59:59', 'cat-002', NOW(), NOW()),

('spt-2', '[LEMLAB] Workshop TRẢI NGHIỆM LÀM GỐM THỦ CÔNG', 'Khóa học trải nghiệm vuốt gốm thủ công bằng tay và vẽ màu trang trí gốm nghệ thuật.', 'https://images.unsplash.com/photo-1565193566173-7a0ee3dbe261?w=800&q=80', 'https://images.unsplash.com/photo-1565193566173-7a0ee3dbe261?w=600&q=80', 'LEMLAB Studio', 'Quận 1, TP. Hồ Chí Minh', 'LEMLAB Team', 'Chị Ngọc Nghệ Nhân', '09:00, 19 Tháng 09, 2026', 0, 350000.0, 'LIVE', 'APPROVED', '2026-09-19 09:00:00', '2026-09-19 12:00:00', '2026-08-01 09:00:00', '2026-09-19 09:00:00', 'cat-004', NOW(), NOW()),

('spt-3', '[FLOWER 1969''S] LUNG TUNG DRUM - TRANG TRÍ TRỐNG LÙNG TÙNG', 'Workshop sáng tạo vẽ và trang trí trống lùng tùng thủ công mang sắc màu dân gian độc đáo.', 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80', 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600&q=80', 'Flower 1969s Workshop', 'Quận 3, TP. Hồ Chí Minh', 'Flower 1969s', 'Nhóm Nghệ Thuật', '14:00, 26 Tháng 09, 2026', 0, 280000.0, 'LIVE', 'APPROVED', '2026-09-26 14:00:00', '2026-09-26 17:00:00', '2026-08-01 09:00:00', '2026-09-26 14:00:00', 'cat-004', NOW(), NOW()),

('event-003', 'Hội Thảo Công Nghệ Halo AI Summit 2026 - Tương Lai AI & Cloud', 'Hội thảo công nghệ AI & Cloud Microservices quy tụ hơn 50 chuyên gia công nghệ hàng đầu.', 'https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=800&q=80', 'https://images.unsplash.com/photo-1540575467063-178a50c2df87?w=600&q=80', 'GEM Center', '8 Nguyễn Bỉnh Khiêm, Đa Kao, Quận 1, TP.HCM', 'Halo Tech Alliance', 'Hội Công Nghệ', '08:30, 05 Tháng 12, 2026', 0, 500000.0, 'LIVE', 'APPROVED', '2026-12-05 08:30:00', '2026-12-05 17:00:00', '2026-08-15 09:00:00', '2026-12-04 23:59:59', 'cat-004', NOW(), NOW()),

('van-gogh-exhibition', 'Triển Lãm Đa Giác Quan Van Gogh Art Lighting Experience', 'Triển lãm nghệ thuật tương tác ánh sáng 360 độ ngắm nhìn kiệt tác của đại tài Van Gogh.', 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?w=800&q=80', 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?w=600&q=80', 'Gigamall Thủ Đức', '240-242 Phạm Văn Đồng, Hiệp Bình Chánh, Thủ Đức, TP.HCM', 'Art Lighting Studio', 'Curator Vietnam', '09:30, 01 Tháng 10, 2026 - 22:00, 31 Tháng 12, 2026', 90, 300000.0, 'LIVE', 'APPROVED', '2026-10-01 09:30:00', '2026-12-31 22:00:00', '2026-08-01 09:00:00', '2026-12-31 20:00:00', 'cat-005', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Seed Cast for NXNX37 & Events
INSERT INTO event_cast (event_id, actor_name) VALUES
('nxnx37-hoc-vien-phep-thuat', 'Bạch Long'),
('nxnx37-hoc-vien-phep-thuat', 'Thanh Thuỷ'),
('nxnx37-hoc-vien-phep-thuat', 'Hoàng Trinh'),
('nxnx37-hoc-vien-phep-thuat', 'Đình Toàn'),
('nxnx37-hoc-vien-phep-thuat', 'Đại Nghĩa'),
('nxnx37-hoc-vien-phep-thuat', 'Mỹ Duyên'),
('nxnx37-hoc-vien-phep-thuat', 'Hồng Ánh'),
('nxnx37-hoc-vien-phep-thuat', 'Quang Thảo'),
('xoay-tron-concert-hoang-dung', 'Hoàng Dũng'),
('xoay-tron-concert-hoang-dung', 'Dàn Nhạc Giao Hưởng Sài Gòn'),
('phung-khanh-linh-tour', 'Phùng Khánh Linh'),
('mat-nham-mat-mo-concert', 'Vũ.'),
('mat-nham-mat-mo-concert', 'Các Ca Sĩ Khách Mời Indie');

-- Seed Gallery Images
INSERT INTO event_gallery (event_id, image_url) VALUES
('nxnx37-hoc-vien-phep-thuat', 'https://images.unsplash.com/photo-1465847899084-d164df4dedc6?w=800&q=80'),
('nxnx37-hoc-vien-phep-thuat', 'https://images.unsplash.com/photo-1507676184212-d03ab07a01bf?w=800&q=80'),
('nxnx37-hoc-vien-phep-thuat', 'https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=800&q=80'),
('xoay-tron-concert-hoang-dung', 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=800&q=80'),
('xoay-tron-concert-hoang-dung', 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800&q=80');

INSERT INTO event_organizer_mapping (event_id, organizer_id)
VALUES
('nxnx37-hoc-vien-phep-thuat', 'org-001'),
('xoay-tron-concert-hoang-dung', 'org-002'),
('idecaf-tam-cam-dai-chien', 'org-001'),
('phung-khanh-linh-tour', 'org-003'),
('mat-nham-mat-mo-concert', 'org-002'),
('dalat-cloud-concert', 'org-003'),
('spt-4', 'org-003'),
('event-002', 'org-003'),
('spt-2', 'org-003'),
('spt-3', 'org-003'),
('event-003', 'org-003'),
('van-gogh-exhibition', 'org-003')
ON CONFLICT DO NOTHING;

-- 3. TICKET DB (ticket_db)
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

INSERT INTO ticket_types (id, event_id, name, price, total_quantity, ticket_type_status, created_at, updated_at)
VALUES
-- NXNX37 Ticket Types
('tt-nxnx-vvip', 'nxnx37-hoc-vien-phep-thuat', 'VVIP', 400000.0, 100, 'ACTIVE', NOW(), NOW()),
('tt-nxnx-vip', 'nxnx37-hoc-vien-phep-thuat', 'VIP', 350000.0, 150, 'ACTIVE', NOW(), NOW()),
('tt-nxnx-normal', 'nxnx37-hoc-vien-phep-thuat', 'THƯỜNG', 300000.0, 200, 'ACTIVE', NOW(), NOW()),
('tt-nxnx-balcony', 'nxnx37-hoc-vien-phep-thuat', 'LẦU', 250000.0, 100, 'COMING_SOON', NOW(), NOW()),

-- Xoay Tròn Concert Ticket Types
('tt-xoay-a2', 'xoay-tron-concert-hoang-dung', 'XOAY TRÒN A2 (SEATED)', 2499000.0, 80, 'ACTIVE', NOW(), NOW()),
('tt-xoay-deu-b', 'xoay-tron-concert-hoang-dung', 'XOAY ĐỀU B (STANDING)', 1700000.0, 150, 'ACTIVE', NOW(), NOW()),
('tt-xoay-b1', 'xoay-tron-concert-hoang-dung', 'XOAY TRÒN B1 (SEATED)', 2499000.0, 80, 'ACTIVE', NOW(), NOW()),
('tt-xoay-deu-a', 'xoay-tron-concert-hoang-dung', 'XOAY ĐỀU A (STANDING)', 1700000.0, 120, 'ACTIVE', NOW(), NOW()),

-- Other Events Ticket Types
('tt-tamcam-vip', 'idecaf-tam-cam-dai-chien', 'Vé VIP', 300000.0, 100, 'ACTIVE', NOW(), NOW()),
('tt-tamcam-std', 'idecaf-tam-cam-dai-chien', 'Vé Thường', 220000.0, 200, 'ACTIVE', NOW(), NOW()),
('tt-phungkhanhlinh-ga', 'phung-khanh-linh-tour', 'Hạng Vé GA Standing', 600000.0, 300, 'ACTIVE', NOW(), NOW()),
('tt-vu-vip', 'mat-nham-mat-mo-concert', 'Vé SVIP Mắt Nhắm', 1800000.0, 50, 'ACTIVE', NOW(), NOW()),
('tt-esports-ga', 'spt-4', 'Vé Đấu Trường Hỗn Chiến', 150000.0, 500, 'ACTIVE', NOW(), NOW()),
('tt-vangogh-std', 'van-gogh-exhibition', 'Vé Triển Lãm Tiêu Chuẩn', 300000.0, 1000, 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 4. MARKETPLACE DB (marketplace_db)
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
('rsl-1', 'xoay-tron-concert-hoang-dung', '66666666-6666-6666-6666-666666666666', 'RSL461904575', true, 2, '19:00, 06 tháng 09, 2026', 'XOAY TRÒN A2 (SEATED)', 'XOAY-TRON-A2', '#06B6D4', 2499000.0, 'ACTIVE', NOW(), NOW()),
('rsl-2', 'xoay-tron-concert-hoang-dung', '66666666-6666-6666-6666-666666666666', 'RSL098398269', true, 2, '19:00, 06 tháng 09, 2026', 'XOAY ĐỀU B (STANDING)', 'XOAY-DEU-B', '#F59E0B', 1700000.0, 'ACTIVE', NOW(), NOW()),
('rsl-3', 'nxnx37-hoc-vien-phep-thuat', '66666666-6666-6666-6666-666666666666', 'RSL469481575', true, 1, '20:00, 25 tháng 09, 2026', 'VVIP (SEATED)', 'KHU VVIP', '#F43F5E', 400000.0, 'ACTIVE', NOW(), NOW()),
('rsl-4', 'phung-khanh-linh-tour', '66666666-6666-6666-6666-666666666666', 'RSL389639474', false, 2, '19:30, 17 tháng 10, 2026', 'GA STANDING', 'KHU SÂN SÂN KHẤU', '#10B981', 600000.0, 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
