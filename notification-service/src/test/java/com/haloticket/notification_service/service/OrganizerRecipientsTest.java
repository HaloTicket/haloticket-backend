package com.haloticket.notification_service.service;

import com.haloticket.notification_service.client.RecipientDirectory;
import com.haloticket.notification_service.client.TicketOwnerClient.Ownership;
import com.haloticket.notification_service.dto.request.AnnouncementTarget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrganizerRecipientsTest {
    private final RecipientDirectory directory = mock(RecipientDirectory.class);
    private final OrganizerRecipients recipients = new OrganizerRecipients(directory);

    @Test
    void aUsedTicketUnderAnotherAliasExcludesTheCanonicalAccountAcrossPages() {
        var first = IntStream.range(0, 200).mapToObj(i -> new Ownership("raw-" + i, true, false)).toList();
        var ids = first.stream().map(Ownership::ownerId).toList();
        when(directory.ticketOwners("event", "owner", "", "", "", 200)).thenReturn(first);
        when(directory.ticketOwners("event", "owner", "", "", "raw-199", 200))
                .thenReturn(List.of(new Ownership("subject-alias", false, true)));
        when(directory.activeBuyerMatches(any(), eq(ids))).thenReturn(List.of("canonical", "other"));
        when(directory.activeBuyerMatches(any(), eq(List.of("subject-alias")))).thenReturn(List.of("canonical"));
        when(directory.activeBuyerMatches(any(), eq(List.of()))).thenReturn(List.of());
        var target = new AnnouncementTarget(AnnouncementTarget.Group.CUSTOMER, AnnouncementTarget.Scope.NOT_CHECKED_IN,
                null, null, null, null);
        var eligible = new ArrayList<String>();
        recipients.forEachBatch("event", "owner", target, () -> {}, eligible::addAll);
        assertThat(eligible).containsExactly("other");
    }

    @Test
    void customerCountDeduplicatesCanonicalAccountsBetweenCursorPages() {
        var first = IntStream.range(0, 200).mapToObj(i -> new Ownership("raw-" + i, true, false)).toList();
        when(directory.ticketOwners("event", "owner", "", "", "", 200)).thenReturn(first);
        when(directory.ticketOwners("event", "owner", "", "", "raw-199", 200))
                .thenReturn(List.of(new Ownership("subject-alias", false, true)));
        when(directory.activeBuyerMatches(any(), anyList())).thenReturn(List.of("canonical", "other"), List.of("canonical"));
        assertThat(recipients.count("event", "owner", AnnouncementTarget.CUSTOMER_ALL)).isEqualTo(2);
    }
}
