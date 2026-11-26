package com.haloticket.notification_service.controller;

import com.haloticket.notification_service.authz.NotificationScopeGuard;
import com.haloticket.notification_service.enums.InboxChannel;
import com.haloticket.notification_service.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationInboxControllerTest {

    @Test
    void inboxDefaultsToPublicAndReadAllUsesSelectedChannel() throws Exception {
        var service = mock(NotificationService.class);
        var scopes = mock(NotificationScopeGuard.class);
        var aliases = Set.of("owner", "jwt-subject");
        when(scopes.inboxAliases("owner", InboxChannel.PUBLIC)).thenReturn(aliases);
        when(scopes.inboxAliases("owner", InboxChannel.STAFF)).thenReturn(aliases);
        when(scopes.inboxAliases("owner", InboxChannel.ALL)).thenReturn(aliases);
        var mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service, scopes)).build();
        var inbox = "/api/notifications/users/owner";

        mvc.perform(get(inbox)).andExpect(status().isOk());
        mvc.perform(get(inbox).param("channel", "STAFF")).andExpect(status().isOk());
        mvc.perform(patch(inbox + "/read-all").param("channel", "ALL")).andExpect(status().isOk());
        verify(service).getUserNotifications(aliases, InboxChannel.PUBLIC);
        verify(service).getUserNotifications(aliases, InboxChannel.STAFF);
        verify(service).markAllAsRead(eq(aliases), any(), eq(InboxChannel.ALL));
    }

    @Test
    void deletingAPersonalItemReturnsNoContent() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new NotificationController(mock(NotificationService.class), mock(NotificationScopeGuard.class))).build();

        mvc.perform(delete("/api/notifications/notification-1")).andExpect(status().isNoContent());
    }
}
