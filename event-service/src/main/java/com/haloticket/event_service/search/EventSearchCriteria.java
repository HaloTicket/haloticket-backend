package com.haloticket.event_service.search;

import java.time.LocalDate;

/**
 * Tiêu chí tìm kiếm sự kiện công khai. Mọi trường đều tùy chọn; không có trường nào thì trả toàn bộ sự kiện đang mở theo trang.
 *
 * @param q từ khóa, so khớp không dấu ở tiêu đề, địa điểm, địa chỉ, thành phố và tên danh mục; mọi từ đều phải xuất hiện
 * @param city thành phố: tên có sẵn ("Hồ Chí Minh", "Hà Nội", "Đà Lạt"), {@code other} cho các nơi còn lại, hoặc chữ tự do
 * @param categoryId id danh mục
 * @param categoryName tên danh mục (dùng khi client chưa biết id)
 * @param from ngày bắt đầu của khoảng thời gian cần giao với sự kiện (tính cả ngày này)
 * @param to ngày cuối của khoảng thời gian (tính cả ngày này)
 * @param free chỉ lấy sự kiện có giá thấp nhất bằng 0
 * @param sort cách sắp xếp
 * @param includeEnded có lấy cả sự kiện đã hết hạn bán hoặc đã kết thúc hay không (mặc định không)
 * @param page số trang bắt đầu từ 0
 * @param size số sự kiện mỗi trang
 */
public record EventSearchCriteria(
        String q,
        String city,
        String categoryId,
        String categoryName,
        LocalDate from,
        LocalDate to,
        boolean free,
        Sort sort,
        boolean includeEnded,
        int page,
        int size) {

    /** Số sự kiện tối đa mỗi trang, để một yêu cầu không kéo về quá nhiều dữ liệu. */
    public static final int MAX_PAGE_SIZE = 50;

    /** Số sự kiện mỗi trang mặc định. */
    public static final int DEFAULT_PAGE_SIZE = 12;

    /** Cách sắp xếp kết quả. */
    public enum Sort {
        /** Sự kiện khớp tiêu đề trước (khi có từ khóa), rồi sự kiện diễn ra sớm nhất. */
        RELEVANCE,
        /** Sự kiện diễn ra sớm nhất trước. */
        SOONEST,
        /** Giá thấp nhất tăng dần; sự kiện chưa có giá xuống cuối. */
        PRICE_ASC,
        /** Giá thấp nhất giảm dần; sự kiện chưa có giá xuống cuối. */
        PRICE_DESC;

        /**
         * Đọc cách sắp xếp từ tham số URL; giá trị lạ hoặc trống thì dùng {@link #RELEVANCE}.
         *
         * @param value giá trị tham số {@code sort}
         * @return cách sắp xếp
         */
        public static Sort parse(String value) {
            if (value == null) return RELEVANCE;
            return switch (value.trim().toLowerCase()) {
                case "soonest" -> SOONEST;
                case "priceasc", "price_asc" -> PRICE_ASC;
                case "pricedesc", "price_desc" -> PRICE_DESC;
                default -> RELEVANCE;
            };
        }
    }

    /** Bản sao với số trang và kích thước đã được giới hạn trong khoảng hợp lệ (trang từ 0, kích thước từ 1 đến {@link #MAX_PAGE_SIZE}). */
    public EventSearchCriteria normalized() {
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return new EventSearchCriteria(q, city, categoryId, categoryName, from, to, free, sort == null ? Sort.RELEVANCE : sort,
                includeEnded, Math.max(page, 0), safeSize);
    }
}
