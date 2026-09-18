package com.haloticket.marketplace_service.domain;

import com.haloticket.marketplace_service.entity.MarketplaceTransaction;
import com.haloticket.marketplace_service.entity.MarketplaceSaga;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.enums.MarketplaceTransactionStatus;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ResaleDomainModelTest {
    @Test
    void marketplaceSagaUsesApplicationAssignedId() throws Exception {
        var id = MarketplaceSaga.class.getDeclaredField("id");
        assertNull(id.getAnnotation(jakarta.persistence.GeneratedValue.class));
    }

    @Test
    void supportsReservationAndPurchaseLifecycleStates() {
        assertDoesNotThrow(() -> ListingStatus.valueOf("RESERVED"));
        assertDoesNotThrow(() -> MarketplaceTransactionStatus.valueOf("PENDING_PAYMENT"));
        assertDoesNotThrow(() -> MarketplaceTransactionStatus.valueOf("PAYMENT_PROCESSING"));
        assertDoesNotThrow(() -> MarketplaceTransactionStatus.valueOf("TRANSFERRING"));
        assertDoesNotThrow(() -> MarketplaceTransactionStatus.valueOf("REFUND_PENDING"));
        assertDoesNotThrow(() -> MarketplaceTransactionStatus.valueOf("REFUNDED"));
        assertDoesNotThrow(() -> MarketplaceTransactionStatus.valueOf("EXPIRED"));
    }

    @Test
    void listingAndTransactionKeepTheSnapshotsNeededToRecoverAResalePurchase() {
        assertTrue(fieldNames(TicketListing.class).containsAll(Set.of(
                "ticketTypeNameSnapshot", "sectionSnapshot", "rowSnapshot", "seatSnapshot",
                "reservedBy", "reservedUntil", "soldAt")));
        assertTrue(fieldNames(MarketplaceTransaction.class).containsAll(Set.of(
                "sellerId", "currency", "paymentReference", "cancelledAt")));
    }

    @Test
    void settlementHasOneLedgerEntryPerMarketplaceTransaction() throws Exception {
        Class<?> settlementClass = Class.forName("com.haloticket.marketplace_service.entity.SellerSettlement");
        assertTrue(fieldNames(settlementClass).containsAll(Set.of(
                "marketplaceTransaction", "sellerId", "grossAmount", "platformFee", "netAmount",
                "currency", "status", "eligibleAt", "settledAt")));

        Table table = settlementClass.getAnnotation(Table.class);
        assertNotNull(table);
        assertTrue(Stream.of(table.uniqueConstraints())
                .anyMatch(constraint -> Set.of(constraint.columnNames()).contains("marketplace_transaction_id")));
    }

    private static Set<String> fieldNames(Class<?> type) {
        return Stream.of(type.getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());
    }
}
