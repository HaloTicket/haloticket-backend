package com.haloticket.event_service.authz;

import com.haloticket.event_service.enums.StaffRole;

import java.util.Set;

/**
 * What one person may do with one event.
 *
 * @param role           the person's active role in the event, or null (a platform admin is reported as OWNER)
 * @param assignmentId   the active staff_assignments row; null for a platform admin 
 * @param inactive       the person has an assignment row for the event but it is not ACTIVE (removed)
 * @param platformAdmin  the caller carries ROLE_ADMIN
 */
public record EventAccess(String eventId, String userId, boolean platformAdmin, StaffRole role, String assignmentId,
                          boolean inactive, Set<Permission> permissions) {

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }
}
