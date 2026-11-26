package com.haloticket.notification_service.service;

import com.haloticket.notification_service.client.RecipientDirectory;
import com.haloticket.notification_service.client.TicketOwnerClient.Ownership;
import com.haloticket.notification_service.dto.request.AnnouncementTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class OrganizerRecipients {
    private static final int PAGE_SIZE = 200;
    private final RecipientDirectory directory;

    public long count(String eventId, String actorId, AnnouncementTarget target) {
        var unique = new HashSet<String>();
        forEachBatch(eventId, actorId, target, () -> {}, unique::addAll);
        return unique.size();
    }

    public void forEachBatch(String eventId, String actorId, AnnouncementTarget target,
                             Runnable checkpoint, Consumer<List<String>> accept) {
        Instant cutoff = Instant.now();
        if (target.staff()) {
            String role = switch (target.scope()) {
                case CHECKIN_STAFF -> "CHECKIN_STAFF";
                case ROLE -> target.role().name();
                default -> null;
            };
            pages(after -> directory.announcementStaff(eventId, actorId, cutoff, role,
                            selector(target.memberId()), after, PAGE_SIZE), Function.identity(), checkpoint,
                    ids -> accept.accept(directory.activeMatches(cutoff, ids)));
            directory.requireAnnouncementAccess(eventId, actorId);
            return;
        }

        Function<String, List<Ownership>> owners = after -> directory.ticketOwners(eventId, actorId,
                selector(target.ticketTypeId()), selector(target.showtimeId()), after, PAGE_SIZE);
        if (target.scope() == AnnouncementTarget.Scope.NOT_CHECKED_IN) {
            // An owner can have VALID and USED tickets under different aliases for the same account.
            var valid = new HashSet<String>();
            var checkedIn = new HashSet<String>();
            pages(owners, Ownership::ownerId, checkpoint, rows -> {
                valid.addAll(directory.activeBuyerMatches(cutoff, rows.stream().filter(Ownership::valid)
                        .map(Ownership::ownerId).toList()));
                checkedIn.addAll(directory.activeBuyerMatches(cutoff, rows.stream().filter(Ownership::checkedIn)
                        .map(Ownership::ownerId).toList()));
            });
            valid.removeAll(checkedIn);
            var ids = valid.stream().sorted().toList();
            for (int start = 0; start < ids.size(); start += PAGE_SIZE) {
                checkpoint.run();
                accept.accept(ids.subList(start, Math.min(start + PAGE_SIZE, ids.size())));
            }
        } else {
            pages(owners, Ownership::ownerId, checkpoint, rows -> accept.accept(directory.activeBuyerMatches(cutoff,
                    rows.stream().filter(row -> target.scope() != AnnouncementTarget.Scope.CHECKED_IN || row.checkedIn())
                            .map(Ownership::ownerId).toList())));
        }
    }

    private static <T> void pages(Function<String, List<T>> read, Function<T, String> id,
                                  Runnable checkpoint, Consumer<List<T>> accept) {
        String after = "";
        while (true) {
            checkpoint.run();
            var page = read.apply(after);
            if (page.size() > PAGE_SIZE) throw new IllegalStateException("Recipient page exceeds the limit");
            String previous = after;
            for (T row : page) {
                String current = id.apply(row);
                if (current == null || current.isBlank() || current.equals(previous)) {
                    throw new IllegalStateException("Invalid recipient cursor");
                }
                previous = current;
            }
            accept.accept(page);
            if (page.size() < PAGE_SIZE) return;
            if (after.equals(previous)) throw new IllegalStateException("Recipient cursor did not advance");
            after = previous;
        }
    }

    private static String selector(String id) {
        return id == null ? "" : id;
    }
}
