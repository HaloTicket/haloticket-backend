package com.haloticket.notification_service.client;

import com.haloticket.notification_service.authz.NotificationScopeProperties;
import com.haloticket.notification_service.dto.response.AnnouncementScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class AnnouncementScanClient {
    private final NotificationScopeProperties scope;
    private final String url;
    private final RestClient client;

    public AnnouncementScanClient(NotificationScopeProperties scope, @Value("${notification.content-scan-url:}") String url) {
        this.scope = scope;
        this.url = url;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500)).build());
        factory.setReadTimeout(Duration.ofSeconds(1));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public AnnouncementScan scan(String title, String message) {
        if (url.isBlank() || scope.getInternalToken().isBlank()) return AnnouncementScan.unavailable();
        try {
            var scan = client.post().uri(url).header(AnnouncementDirectoryClient.INTERNAL_TOKEN, scope.getInternalToken())
                    .body(new ScanInput(title, message)).retrieve().body(AnnouncementScan.class);
            if (scan != null && scan.status() == AnnouncementScan.Status.COMPLETE && scan.score() != null
                    && scan.score() >= 0 && scan.score() <= 100 && scan.level() != null && scan.findings() != null
                    && scan.findings().size() <= 50
                    && scan.findings().stream().allMatch(value -> value != null && value.length() <= 500)) {
                return scan;
            }
        } catch (RestClientException | IllegalArgumentException ignored) {
            // Scanning is advisory. The admin can still review the original content.
        }
        return AnnouncementScan.unavailable();
    }

    private record ScanInput(String title, String message) {}
}
