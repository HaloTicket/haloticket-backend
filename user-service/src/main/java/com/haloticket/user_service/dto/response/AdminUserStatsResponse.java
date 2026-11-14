package com.haloticket.user_service.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** Head counts for the user management page, in one call instead of one list request per role. */
@Data
@Builder
public class AdminUserStatsResponse {
    private long total;
    /** role name to the number of users holding it (a user with two roles counts under both). */
    private Map<String, Long> byRole;
    /** status name to the number of users in it. */
    private Map<String, Long> byStatus;
}
