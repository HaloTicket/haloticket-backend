package com.haloticket.event_service.search;

import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.enums.EventStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dựng điều kiện truy vấn và thứ tự sắp xếp cho tìm kiếm sự kiện công khai. Mọi so khớp chữ đều không phân biệt dấu và hoa thường
 * bằng hàm {@code translate()} của SQL (xem {@link VietnameseText}), nên chạy được trên PostgreSQL mà không cần extension.
 */
public final class EventSpecifications {

    /** Số từ khóa tối đa được dùng, để truy vấn không phình quá lớn. */
    private static final int MAX_TOKENS = 6;

    /**
     * Các cách gọi (đã bỏ dấu, chữ thường) của từng thành phố có tên sẵn. Không dùng chữ viết tắt hai ký tự (hn, sg) vì SQL
     * LIKE không có ranh giới từ nên sẽ khớp nhầm bên trong từ khác.
     */
    static final Map<String, List<String>> CITY_ALIASES = Map.of(
            "ho chi minh", List.of("ho chi minh", "hcm", "tphcm", "sai gon", "saigon"),
            "ha noi", List.of("ha noi", "hanoi"),
            "da lat", List.of("da lat", "dalat"));

    /** Giá trị {@code city} cho "các nơi khác ngoài những thành phố có tên sẵn". */
    public static final String CITY_OTHER = "other";

    private EventSpecifications() {
    }

    /**
     * Điều kiện và thứ tự cho một lần tìm kiếm.
     *
     * @param criteria tiêu chí đã chuẩn hóa
     * @param now thời điểm hiện tại (dùng để loại sự kiện đã hết hạn)
     * @return đặc tả truy vấn; thứ tự sắp xếp chỉ được áp cho truy vấn dữ liệu, không áp cho truy vấn đếm
     */
    public static Specification<Event> forSearch(EventSearchCriteria criteria, LocalDateTime now) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();

            // chỉ sự kiện đã duyệt và công khai (cùng định nghĩa "còn hiệu lực" với chatbot AI)
            where.add(root.get("eventStatus").in(EventStatus.APPROVED, EventStatus.LIVE));
            where.add(cb.or(cb.isNull(root.get("isPrivate")), cb.isFalse(root.get("isPrivate"))));

            if (!criteria.includeEnded()) {
                Expression<LocalDateTime> saleEnd = root.get("saleEndTime");
                Expression<LocalDateTime> end = root.get("endTime");
                where.add(cb.or(
                        cb.greaterThan(saleEnd, now),
                        cb.and(cb.isNull(saleEnd), cb.or(cb.isNull(end), cb.greaterThan(end, now)))));
            }

            Join<Event, EventCategory> category = root.join("category", JoinType.LEFT);
            List<String> tokens = tokens(criteria.q());
            for (String token : tokens) {
                String pattern = "%" + VietnameseText.escapeLike(token) + "%";
                where.add(cb.or(
                        cb.like(fold(cb, root.get("title")), pattern, '\\'),
                        cb.like(fold(cb, root.get("locationName")), pattern, '\\'),
                        cb.like(fold(cb, root.get("address")), pattern, '\\'),
                        cb.like(fold(cb, root.get("city")), pattern, '\\'),
                        cb.like(fold(cb, category.get("name")), pattern, '\\')));
            }

            Predicate cityPredicate = cityPredicate(cb, root, criteria.city());
            if (cityPredicate != null) where.add(cityPredicate);

            if (notBlank(criteria.categoryId())) {
                where.add(cb.equal(category.get("id"), criteria.categoryId().trim()));
            }
            if (notBlank(criteria.categoryName())) {
                where.add(cb.equal(category.get("name"), criteria.categoryName().trim()));
            }

            if (criteria.free()) {
                where.add(cb.equal(root.<Double>get("minPrice"), 0.0));
            }

            // khoảng thời gian diễn ra của sự kiện giao với khoảng đã chọn; sự kiện chưa có ngày bắt đầu thì không khớp
            if (criteria.from() != null || criteria.to() != null) {
                Expression<LocalDateTime> start = root.get("startTime");
                where.add(cb.isNotNull(start));
                if (criteria.to() != null) {
                    where.add(cb.lessThan(start, criteria.to().plusDays(1).atStartOfDay()));
                }
                if (criteria.from() != null) {
                    Expression<LocalDateTime> endOrStart = cb.coalesce(root.<LocalDateTime>get("endTime"), start);
                    where.add(cb.greaterThanOrEqualTo(endOrStart, criteria.from().atStartOfDay()));
                }
            }

            if (isDataQuery(query.getResultType())) {
                query.orderBy(orderBy(cb, root, criteria, tokens));
                query.distinct(false);
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }

    /** Truy vấn lấy dữ liệu (không phải truy vấn đếm tổng): chỉ lúc này mới được áp thứ tự sắp xếp. */
    private static boolean isDataQuery(Class<?> resultType) {
        return resultType != Long.class && resultType != long.class;
    }

    /** Điều kiện theo thành phố; {@code null} khi không lọc. */
    private static Predicate cityPredicate(CriteriaBuilder cb, Root<Event> root, String city) {
        if (!notBlank(city)) return null;
        Expression<String> haystack = cb.concat(cb.concat(cb.concat(
                fold(cb, root.get("city")), " | "), fold(cb, root.get("address"))), cb.concat(" | ", fold(cb, root.get("locationName"))));

        String key = VietnameseText.fold(city.trim());
        if (CITY_OTHER.equals(key)) {
            List<Predicate> none = new ArrayList<>();
            for (List<String> aliases : CITY_ALIASES.values()) {
                for (String alias : aliases) {
                    none.add(cb.not(cb.like(haystack, "%" + VietnameseText.escapeLike(alias) + "%", '\\')));
                }
            }
            return cb.and(none.toArray(new Predicate[0]));
        }

        List<String> aliases = CITY_ALIASES.getOrDefault(key, List.of(key));
        List<Predicate> any = new ArrayList<>();
        for (String alias : aliases) {
            any.add(cb.like(haystack, "%" + VietnameseText.escapeLike(alias) + "%", '\\'));
        }
        return cb.or(any.toArray(new Predicate[0]));
    }

    /** Thứ tự sắp xếp: kèm ưu tiên khớp tiêu đề khi có từ khóa, ngày hoặc giá chưa rõ luôn xuống cuối, và id để thứ tự ổn định giữa các trang. */
    private static List<Order> orderBy(CriteriaBuilder cb, Root<Event> root, EventSearchCriteria criteria, List<String> tokens) {
        List<Order> orders = new ArrayList<>();
        Expression<LocalDateTime> start = root.get("startTime");
        Expression<Double> price = root.get("minPrice");

        switch (criteria.sort()) {
            case PRICE_ASC, PRICE_DESC -> {
                orders.add(cb.asc(cb.<Integer>selectCase().when(cb.isNull(price), 1).otherwise(0)));
                orders.add(criteria.sort() == EventSearchCriteria.Sort.PRICE_ASC ? cb.asc(price) : cb.desc(price));
            }
            case RELEVANCE -> {
                if (!tokens.isEmpty()) {
                    Predicate titleMatchesAll = cb.and(tokens.stream()
                            .map(token -> cb.like(fold(cb, root.get("title")), "%" + VietnameseText.escapeLike(token) + "%", '\\'))
                            .toArray(Predicate[]::new));
                    orders.add(cb.asc(cb.<Integer>selectCase().when(titleMatchesAll, 0).otherwise(1)));
                }
            }
            default -> {
            }
        }
        orders.add(cb.asc(cb.<Integer>selectCase().when(cb.isNull(start), 1).otherwise(0)));
        orders.add(cb.asc(start));
        orders.add(cb.asc(root.get("id")));
        return orders;
    }

    /** Biểu thức SQL: {@code lower(translate(coalesce(cột, ''), có dấu, không dấu))}. */
    private static Expression<String> fold(CriteriaBuilder cb, Expression<String> column) {
        return cb.lower(cb.function("translate", String.class,
                cb.coalesce(column, ""), cb.literal(VietnameseText.TRANSLATE_FROM), cb.literal(VietnameseText.TRANSLATE_TO)));
    }

    /** Tách từ khóa thành các từ đã chuẩn hóa (tối đa {@link #MAX_TOKENS} từ, bỏ từ rỗng). */
    static List<String> tokens(String q) {
        if (!notBlank(q)) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String part : VietnameseText.fold(q).trim().split("\\s+")) {
            if (!part.isBlank() && tokens.size() < MAX_TOKENS) tokens.add(part);
        }
        return tokens;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
