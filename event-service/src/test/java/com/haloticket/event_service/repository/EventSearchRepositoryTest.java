package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.search.EventSearchCriteria;
import com.haloticket.event_service.search.EventSearchCriteria.Sort;
import com.haloticket.event_service.search.EventSpecifications;
import com.haloticket.event_service.search.VietnameseText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tìm kiếm sự kiện công khai chạy thật trên H2: lọc không dấu, thành phố, danh mục, ngày, miễn phí, hạn bán, quyền hiển thị,
 * sắp xếp và phân trang (kể cả truy vấn đếm tổng).
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
class EventSearchRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.haloticket.event_service.entity")
    @EnableJpaRepositories("com.haloticket.event_service.repository")
    static class TestApp {}

    @Autowired EventRepository events;
    @Autowired EventCategoryRepository categories;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 2, 10, 0);
    private EventCategory music;
    private EventCategory theatre;

    @BeforeEach
    void seed() {
        music = categories.save(EventCategory.builder().name("Nhạc sống").build());
        theatre = categories.save(EventCategory.builder().name("Sân khấu").build());

        save("Đêm nhạc Hoàng Dũng", music, "Nhà hát Hòa Bình", "Hồ Chí Minh", "Quận 10, TP.HCM", 800_000.0, NOW.plusDays(10), EventStatus.APPROVED, false);
        save("Free Jazz Night", music, "Phố đi bộ", "Hà Nội", "Hoàn Kiếm, Hà Nội", 0.0, NOW.plusDays(3), EventStatus.LIVE, false);
        save("Kịch Lôi Vũ", theatre, "Nhà hát Lớn", "Đà Lạt", "Phường 1, Đà Lạt", 200_000.0, NOW.plusDays(20), EventStatus.APPROVED, false);
        save("Show Cần Thơ", music, "Sân vận động", "Cần Thơ", "Ninh Kiều", 500_000.0, NOW.plusDays(5), EventStatus.APPROVED, false);
        save("Bản nháp chưa duyệt", music, "X", "Hà Nội", "Y", 100.0, NOW.plusDays(2), EventStatus.PENDING, false);
        save("Sự kiện riêng tư", music, "X", "Hà Nội", "Y", 100.0, NOW.plusDays(2), EventStatus.APPROVED, true);
        // đã kết thúc: hết hạn bán và hết thời gian diễn ra
        Event old = save("Đêm nhạc đã qua", music, "Z", "Hà Nội", "Z", 100.0, NOW.minusDays(30), EventStatus.APPROVED, false);
        old.setEndTime(NOW.minusDays(29));
        old.setSaleEndTime(NOW.minusDays(31));
        events.save(old);
        // chưa có giá
        save("Chưa có giá", theatre, "W", "Hà Nội", "W", null, NOW.plusDays(7), EventStatus.APPROVED, false);
    }

    private Event save(String title, EventCategory category, String location, String city, String address, Double minPrice,
                       LocalDateTime start, EventStatus status, boolean isPrivate) {
        return events.save(Event.builder().title(title).category(category).locationName(location).city(city).address(address)
                .minPrice(minPrice).startTime(start).endTime(start.plusHours(3)).eventStatus(status).isPrivate(isPrivate).build());
    }

    private EventSearchCriteria criteria(String q, String city, String categoryName, LocalDate from, LocalDate to, boolean free,
                                         Sort sort, boolean includeEnded, int page, int size) {
        return new EventSearchCriteria(q, city, null, categoryName, from, to, free, sort, includeEnded, page, size).normalized();
    }

    private EventSearchCriteria all() {
        return criteria(null, null, null, null, null, false, Sort.RELEVANCE, false, 0, 50);
    }

    private List<String> titles(EventSearchCriteria c) {
        return events.findAll(EventSpecifications.forSearch(c, NOW), PageRequest.of(c.page(), c.size()))
                .map(Event::getTitle).getContent();
    }

    @Test
    void translateStringsHaveTheSameLength() {
        assertThat(VietnameseText.TRANSLATE_FROM).hasSameSizeAs(VietnameseText.TRANSLATE_TO);
        assertThat(VietnameseText.fold("Đà Lạt – HÀ NỘI")).isEqualTo("da lat – ha noi");
    }

    @Test
    void onlyApprovedPublicUpcomingEventsAreReturned() {
        assertThat(titles(all())).containsExactlyInAnyOrder(
                "Đêm nhạc Hoàng Dũng", "Free Jazz Night", "Kịch Lôi Vũ", "Show Cần Thơ", "Chưa có giá");
    }

    @Test
    void includeEndedAlsoReturnsPastEventsButStillNotDraftsOrPrivate() {
        List<String> all = titles(criteria(null, null, null, null, null, false, Sort.RELEVANCE, true, 0, 50));
        assertThat(all).contains("Đêm nhạc đã qua").doesNotContain("Bản nháp chưa duyệt", "Sự kiện riêng tư");
    }

    @Test
    void keywordMatchesWithoutAccentsAndAnyWordOrder() {
        assertThat(titles(criteria("hoang dung", null, null, null, null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactly("Đêm nhạc Hoàng Dũng");
        assertThat(titles(criteria("DUNG HOANG", null, null, null, null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactly("Đêm nhạc Hoàng Dũng");
        assertThat(titles(criteria("kich", null, null, null, null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactly("Kịch Lôi Vũ");
        // khớp cả địa điểm và danh mục
        assertThat(titles(criteria("nha hat", null, null, null, null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactlyInAnyOrder("Đêm nhạc Hoàng Dũng", "Kịch Lôi Vũ");
        assertThat(titles(criteria("san khau", null, null, null, null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactlyInAnyOrder("Kịch Lôi Vũ", "Chưa có giá");
    }

    @Test
    void likeWildcardsInTheKeywordAreTreatedAsPlainText() {
        assertThat(titles(criteria("%", null, null, null, null, false, Sort.RELEVANCE, false, 0, 50))).isEmpty();
        assertThat(titles(criteria("_", null, null, null, null, false, Sort.RELEVANCE, false, 0, 50))).isEmpty();
    }

    @Test
    void cityAliasesAndOther() {
        assertThat(titles(criteria(null, "Hồ Chí Minh", null, null, null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactly("Đêm nhạc Hoàng Dũng");
        assertThat(titles(criteria(null, "Hà Nội", null, null, null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactlyInAnyOrder("Free Jazz Night", "Chưa có giá");
        assertThat(titles(criteria(null, "Đà Lạt", null, null, null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactly("Kịch Lôi Vũ");
        assertThat(titles(criteria(null, "other", null, null, null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactly("Show Cần Thơ");
    }

    @Test
    void categoryAndFreeFilters() {
        assertThat(titles(criteria(null, null, "Sân khấu", null, null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactlyInAnyOrder("Kịch Lôi Vũ", "Chưa có giá");
        // chưa có giá không phải miễn phí
        assertThat(titles(criteria(null, null, null, null, null, true, Sort.RELEVANCE, false, 0, 50)))
                .containsExactly("Free Jazz Night");
    }

    @Test
    void dateRangeIsInclusiveAndEventsWithoutDatesAreExcluded() {
        LocalDate day = NOW.toLocalDate();
        assertThat(titles(criteria(null, null, null, day.plusDays(3), day.plusDays(5), false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactlyInAnyOrder("Free Jazz Night", "Show Cần Thơ");
        assertThat(titles(criteria(null, null, null, day.plusDays(20), null, false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactly("Kịch Lôi Vũ");
        assertThat(titles(criteria(null, null, null, null, day.plusDays(3), false, Sort.RELEVANCE, false, 0, 50)))
                .containsExactly("Free Jazz Night");
    }

    @Test
    void sortingByDatePriceAndRelevance() {
        assertThat(titles(criteria(null, null, null, null, null, false, Sort.SOONEST, false, 0, 50)))
                .containsExactly("Free Jazz Night", "Show Cần Thơ", "Chưa có giá", "Đêm nhạc Hoàng Dũng", "Kịch Lôi Vũ");
        assertThat(titles(criteria(null, null, null, null, null, false, Sort.PRICE_ASC, false, 0, 50)))
                .containsExactly("Free Jazz Night", "Kịch Lôi Vũ", "Show Cần Thơ", "Đêm nhạc Hoàng Dũng", "Chưa có giá");
        assertThat(titles(criteria(null, null, null, null, null, false, Sort.PRICE_DESC, false, 0, 50)))
                .containsExactly("Đêm nhạc Hoàng Dũng", "Show Cần Thơ", "Kịch Lôi Vũ", "Free Jazz Night", "Chưa có giá");
        // từ khóa: sự kiện khớp tiêu đề được xếp trước sự kiện chỉ khớp địa điểm hoặc danh mục
        assertThat(titles(criteria("nhac", null, null, null, null, false, Sort.RELEVANCE, false, 0, 50)).get(0))
                .isEqualTo("Đêm nhạc Hoàng Dũng");
    }

    @Test
    void paginationReportsTheTotalAndDoesNotRepeatEvents() {
        EventSearchCriteria first = criteria(null, null, null, null, null, false, Sort.SOONEST, false, 0, 2);
        Page<Event> p0 = events.findAll(EventSpecifications.forSearch(first, NOW), PageRequest.of(0, 2));
        Page<Event> p1 = events.findAll(EventSpecifications.forSearch(first, NOW), PageRequest.of(1, 2));
        Page<Event> p2 = events.findAll(EventSpecifications.forSearch(first, NOW), PageRequest.of(2, 2));

        assertThat(p0.getTotalElements()).isEqualTo(5);
        assertThat(p0.getTotalPages()).isEqualTo(3);
        List<String> seen = new java.util.ArrayList<>();
        p0.forEach(e -> seen.add(e.getTitle()));
        p1.forEach(e -> seen.add(e.getTitle()));
        p2.forEach(e -> seen.add(e.getTitle()));
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(p2.getContent()).hasSize(1);
    }

    @Test
    void sizeIsCappedAndNegativePageBecomesZero() {
        EventSearchCriteria c = new EventSearchCriteria(null, null, null, null, null, null, false, null, false, -3, 9999).normalized();
        assertThat(c.page()).isZero();
        assertThat(c.size()).isEqualTo(EventSearchCriteria.MAX_PAGE_SIZE);
        assertThat(new EventSearchCriteria(null, null, null, null, null, null, false, null, false, 0, 0).normalized().size())
                .isEqualTo(EventSearchCriteria.DEFAULT_PAGE_SIZE);
        assertThat(Sort.parse("priceAsc")).isEqualTo(Sort.PRICE_ASC);
        assertThat(Sort.parse("bậy")).isEqualTo(Sort.RELEVANCE);
        assertThat(Sort.parse(null)).isEqualTo(Sort.RELEVANCE);
    }

    @Test
    void publicListNeverContainsDraftsPendingRejectedOrPrivateEvents() {
        List<String> titles = events.findPublicEvents().stream().map(Event::getTitle).toList();

        assertThat(titles).contains("Đêm nhạc Hoàng Dũng", "Free Jazz Night", "Kịch Lôi Vũ", "Show Cần Thơ", "Chưa có giá", "Đêm nhạc đã qua");
        assertThat(titles).doesNotContain("Bản nháp chưa duyệt", "Sự kiện riêng tư");
    }

    @Test
    void draftAndRejectedStatusesAreExcludedFromThePublicList() {
        save("Nháp", music, "X", "Hà Nội", "Y", 0.0, NOW.plusDays(2), EventStatus.DRAFT, false);
        save("Bị từ chối", music, "X", "Hà Nội", "Y", 0.0, NOW.plusDays(2), EventStatus.CANCELLED, false);
        save("Isprivate null", music, "X", "Hà Nội", "Y", 0.0, NOW.plusDays(2), EventStatus.LIVE, false)
                .setIsPrivate(null);

        List<String> titles = events.findPublicEvents().stream().map(Event::getTitle).toList();
        assertThat(titles).doesNotContain("Nháp", "Bị từ chối").contains("Isprivate null");
    }
}
