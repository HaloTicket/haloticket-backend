package com.haloticket.notification_service.client;

import com.haloticket.notification_service.authz.NotificationScopeProperties;
import com.haloticket.notification_service.dto.response.AnnouncementScan;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AnnouncementScanClientTest {
    @Test
    void postsContentWithSharedTokenAndFallsBackWhenServiceFails() throws Exception {
        var received = new AtomicReference<List<String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/notification-content-scan", exchange -> {
            try (exchange) {
                received.set(List.of(exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] response = """
                        {"status":"COMPLETE", "score":34, "level":"MEDIUM", "findings":["Reason"]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        var scope = new NotificationScopeProperties();
        scope.setInternalToken("shared-token");
        var scanner = new AnnouncementScanClient(scope,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/notification-content-scan");
        try {
            assertThat(scanner.scan("Announcement title", "Announcement message"))
                    .isEqualTo(new AnnouncementScan(AnnouncementScan.Status.COMPLETE, 34, AnnouncementScan.Level.MEDIUM,
                            List.of("Reason")));
            assertThat(received.get().subList(0, 2)).containsExactly("POST", "shared-token");
            assertThat(received.get().getLast()).contains("Announcement title", "Announcement message");
            server.removeContext("/internal/notification-content-scan");
            assertThat(scanner.scan("Title", "Message")).isEqualTo(AnnouncementScan.unavailable());
        } finally {
            server.stop(0);
        }
    }
}
