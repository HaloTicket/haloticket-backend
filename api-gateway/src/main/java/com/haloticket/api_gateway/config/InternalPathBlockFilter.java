package com.haloticket.api_gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Service-to-service endpoints (".../internal/...") and management paths must never be reachable through the
 * gateway. Spring's path patterns cannot express "an internal segment at any depth", so the request path is
 * normalised first (percent-decoding, repeated slashes, "." and "..", matrix parameters, backslashes, case) and
 * then checked. Runs before Spring Security and routing and answers 404, like an unknown path.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalPathBlockFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalPathBlockFilter.class);

    private static final String INTERNAL_SEGMENT = "internal";
    private static final Set<String> BLOCKED_FIRST_SEGMENTS = Set.of("actuator", "eureka");
    private static final int MAX_DECODE_ROUNDS = 4;

    /**
     * Actuator runs on its own port (management.server.port), which is not published: only containers in the compose network
     * reach it. A request that really arrived on that port may read /actuator/**; everything on the public port stays blocked.
     */
    @Value("${management.server.port:0}")
    private int managementPort;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        if (arrivedOnManagementPort(exchange.getRequest().getLocalAddress(), managementPort) && isActuatorPath(rawPath)) {
            return chain.filter(exchange);
        }
        if (isBlocked(rawPath)) {
            log.warn("Blocked internal/management path {} {}", exchange.getRequest().getMethod(), printable(rawPath));
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    static boolean arrivedOnManagementPort(java.net.InetSocketAddress local, int managementPort) {
        return local != null && managementPort > 0 && local.getPort() == managementPort;
    }

    /** Only a plain /actuator or /actuator/... path: no encoded or dotted tricks (those still go through isBlocked). */
    static boolean isActuatorPath(String rawPath) {
        return rawPath != null && (rawPath.equals("/actuator") || rawPath.startsWith("/actuator/")) && !rawPath.contains("%")
                && !rawPath.contains("..") && !rawPath.contains("\\") && !rawPath.contains(";");
    }

    static boolean isBlocked(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return false;
        String decoded;
        try {
            decoded = decodeFully(rawPath);
        } catch (IllegalArgumentException malformedEncoding) {
            return true;
        }
        List<String> segments = segments(decoded);
        if (segments.contains(INTERNAL_SEGMENT)) return true;
        List<String> resolved = resolveDots(segments);
        if (resolved.contains(INTERNAL_SEGMENT)) return true;
        return !resolved.isEmpty() && BLOCKED_FIRST_SEGMENTS.contains(resolved.get(0));
    }

    private static String decodeFully(String path) {
        String current = path;
        for (int round = 0; round < MAX_DECODE_ROUNDS; round++) {
            String next = URLDecoder.decode(current.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (next.equals(current)) return current;
            current = next;
        }
        return current;
    }

    private static List<String> segments(String path) {
        return java.util.Arrays.stream(path.replace('\\', '/').split("/"))
                .map(segment -> {
                    int matrix = segment.indexOf(';');
                    return (matrix >= 0 ? segment.substring(0, matrix) : segment).strip().toLowerCase(Locale.ROOT);
                })
                .filter(segment -> !segment.isEmpty())
                .toList();
    }

    private static List<String> resolveDots(List<String> segments) {
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : segments) {
            if (segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (!stack.isEmpty()) stack.removeLast();
                continue;
            }
            stack.addLast(segment);
        }
        return List.copyOf(stack);
    }

    private static String printable(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "?");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }
}
