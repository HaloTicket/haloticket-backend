package com.haloticket.notification_service.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnnouncementTarget(Group group, Scope scope, String ticketTypeId, String showtimeId, Role role,
                                 String memberId) {
    public static final AnnouncementTarget CUSTOMER_ALL = new AnnouncementTarget(Group.CUSTOMER, Scope.ALL,
            null, null, null, null);

    public enum Group { CUSTOMER, STAFF }
    public enum Scope { ALL, TICKET_TYPE, SHOWTIME, CHECKED_IN, NOT_CHECKED_IN, CHECKIN_STAFF, ROLE, MEMBER }
    public enum Role { OWNER, ADMIN, MANAGER, CHECKIN_STAFF }

    public AnnouncementTarget {
        if (group == null || scope == null) throw new IllegalArgumentException("A target group and scope are required");
        boolean allowed = switch (scope) {
            case ALL -> true;
            case TICKET_TYPE, SHOWTIME, CHECKED_IN, NOT_CHECKED_IN -> group == Group.CUSTOMER;
            case CHECKIN_STAFF, ROLE, MEMBER -> group == Group.STAFF;
        };
        if (!allowed || !matchesSelector(ticketTypeId, scope == Scope.TICKET_TYPE)
                || !matchesSelector(showtimeId, scope == Scope.SHOWTIME)
                || !matchesSelector(memberId, scope == Scope.MEMBER)
                || (role != null) != (scope == Scope.ROLE)) {
            throw new IllegalArgumentException("Invalid target scope or selector");
        }
    }

    public boolean staff() {
        return group == Group.STAFF;
    }

    private static boolean matchesSelector(String id, boolean required) {
        return required ? id != null && id.matches("[a-zA-Z0-9_-]{1,128}") : id == null;
    }
}
