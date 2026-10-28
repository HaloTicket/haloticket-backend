package com.haloticket.event_service.authz;

import com.haloticket.event_service.enums.StaffRole;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.haloticket.event_service.authz.Permission.*;

/** The one table that says which event role holds which permission. Every other service asks event-service. */
public final class RolePermissions {

    private static final Map<StaffRole, Set<Permission>> TABLE = new EnumMap<>(StaffRole.class);

    static {
        TABLE.put(StaffRole.OWNER, EnumSet.allOf(Permission.class));
        TABLE.put(StaffRole.ADMIN, EnumSet.of(EVENT_EDIT, POLICY_WRITE, POLICY_READ, VOUCHER_MANAGE, MEMBERS_VIEW, MEMBERS_MANAGE,
                MARKETPLACE_MODERATE, MARKETPLACE_VIEW, ORDERS_VIEW, ANALYTICS_VIEW, CHECKIN_SCAN, CHECKIN_VIEW));
        TABLE.put(StaffRole.MANAGER, EnumSet.of(POLICY_READ, MEMBERS_VIEW, MARKETPLACE_VIEW, ORDERS_VIEW, ANALYTICS_VIEW,
                CHECKIN_SCAN, CHECKIN_VIEW));
        TABLE.put(StaffRole.CHECKIN_STAFF, EnumSet.of(CHECKIN_SCAN, CHECKIN_VIEW));
    }

    private RolePermissions() {}

    public static Set<Permission> of(StaffRole role) {
        return role == null ? Set.of() : Set.copyOf(TABLE.getOrDefault(role, Set.of()));
    }

    public static Set<Permission> all() {
        return EnumSet.allOf(Permission.class);
    }
}
