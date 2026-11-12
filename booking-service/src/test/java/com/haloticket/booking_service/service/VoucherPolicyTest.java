package com.haloticket.booking_service.service;

import com.haloticket.booking_service.dto.response.VoucherResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a voucher may be used for, and that every refusal comes with a reason the buyer can read. */
class VoucherPolicyTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 12, 0);

    private VoucherResponse.VoucherResponseBuilder base() {
        return VoucherResponse.builder().id("v1").isActive(true).status("ACTIVE")
                .discountType("PERCENTAGE").discountValue(10.0).applyTicketLimitType("UNLIMITED").applyScope("ALL");
    }

    private double discount(VoucherResponse v, int tickets, String showtime) {
        return VoucherPolicy.discountFor(v, tickets, 1_000_000, showtime, NOW, () -> 0L);
    }

    @Test
    void aPercentageIsTakenOffTheSubtotal() {
        assertThat(discount(base().build(), 2, "st1")).isEqualTo(100_000.0);
    }

    @Test
    void aFixedAmountNeverExceedsTheSubtotal() {
        VoucherResponse v = base().discountType("FIXED").discountValue(5_000_000.0).build();
        assertThat(discount(v, 1, "st1")).isEqualTo(1_000_000.0);
    }

    @Test
    void aDraftOrSwitchedOffVoucherIsRefused() {
        assertThatThrownBy(() -> discount(base().status("DRAFT").isActive(false).build(), 1, "st1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("không khả dụng");
        assertThatThrownBy(() -> discount(base().status("INACTIVE").isActive(false).build(), 1, "st1"))
                .hasMessageContaining("không khả dụng");
        assertThatThrownBy(() -> discount(null, 1, "st1")).hasMessageContaining("không tồn tại");
    }

    @Test
    void outsideItsDatesItIsRefused() {
        assertThatThrownBy(() -> discount(base().startDate("13/11/2026 00:00").build(), 1, "st1"))
                .hasMessageContaining("chưa có hiệu lực");
        assertThatThrownBy(() -> discount(base().endDate("11/11/2026 23:59").build(), 1, "st1"))
                .hasMessageContaining("hết hạn");
        assertThat(discount(base().startDate("01/11/2026 00:00").endDate("30/11/2026 00:00").build(), 1, "st1")).isPositive();
    }

    @Test
    void aVoucherWithNoQuantityLeftIsRefused() {
        VoucherResponse v = base().applyTicketLimitType("LIMITED").ticketLimitCount(10).usedCount(10).build();
        assertThatThrownBy(() -> discount(v, 1, "st1")).hasMessageContaining("hết lượt");
    }

    @Test
    void anOrderLargerThanWhatIsLeftIsRefusedWithTheNumberLeft() {
        VoucherResponse v = base().applyTicketLimitType("LIMITED").ticketLimitCount(10).usedCount(8).build();
        assertThatThrownBy(() -> discount(v, 3, "st1")).hasMessageContaining("còn 2 lượt");
        assertThat(discount(v, 2, "st1")).isPositive();
    }

    @Test
    void theTicketsPerOrderBoundsAreEnforced() {
        VoucherResponse v = base().minTicketsPerOrder(2).maxTicketsPerOrder(4).build();
        assertThatThrownBy(() -> discount(v, 1, "st1")).hasMessageContaining("từ 2 vé");
        assertThatThrownBy(() -> discount(v, 5, "st1")).hasMessageContaining("tối đa 4 vé");
        assertThat(discount(v, 4, "st1")).isPositive();
    }

    @Test
    void theOrdersPerBuyerLimitUsesThePaidOrdersAndIsOnlyAskedWhenSet() {
        VoucherResponse limited = base().maxOrdersPerBuyer(2).build();
        assertThatThrownBy(() -> VoucherPolicy.discountFor(limited, 1, 1000, "st1", NOW, () -> 2L))
                .hasMessageContaining("tối đa 2 lần");
        assertThat(VoucherPolicy.discountFor(limited, 1, 1000, "st1", NOW, () -> 1L)).isPositive();

        AtomicInteger asked = new AtomicInteger();
        VoucherPolicy.discountFor(base().build(), 1, 1000, "st1", NOW, () -> { asked.incrementAndGet(); return 0L; });
        assertThat(asked).hasValue(0);
    }

    @Test
    void aShowtimeLimitedVoucherNeedsTheExactShowtimeId() {
        VoucherResponse v = base().applyScope("LIMITED_SHOWTIMES").applicableShowtimes(List.of("st-1", "st-2")).build();
        assertThat(discount(v, 1, "st-2")).isPositive();
        assertThatThrownBy(() -> discount(v, 1, "st-3")).hasMessageContaining("suất diễn");
        assertThatThrownBy(() -> discount(v, 1, null)).hasMessageContaining("suất diễn");
        // the old loose match let a longer id or a label through; now only the id itself matches
        assertThatThrownBy(() -> discount(v, 1, "st-")).hasMessageContaining("suất diễn");
        assertThatThrownBy(() -> discount(v, 1, "Suất 1: 19:00")).hasMessageContaining("suất diễn");
    }

    @Test
    void aShowtimeLimitedVoucherWithAnEmptyListAppliesToNothing() {
        VoucherResponse v = base().applyScope("LIMITED_SHOWTIMES").applicableShowtimes(List.of()).build();
        assertThatThrownBy(() -> discount(v, 1, "st-1")).hasMessageContaining("suất diễn");
    }
}
