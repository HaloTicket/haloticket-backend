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
 * Các endpoint giữa các service (".../internal/...") và đường dẫn quản trị tuyệt đối không được truy cập qua
 * gateway. Mẫu đường dẫn của Spring không diễn đạt được "đoạn internal ở độ sâu bất kỳ", nên đường dẫn request
 * được chuẩn hóa trước (giải mã phần trăm, dấu gạch chéo lặp, "." và "..", tham số matrix, dấu gạch ngược, chữ hoa)
 * rồi mới kiểm tra. Chạy trước Spring Security và định tuyến, trả 404 như một đường dẫn không tồn tại.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalPathBlockFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalPathBlockFilter.class);

    private static final String INTERNAL_SEGMENT = "internal";
    private static final Set<String> BLOCKED_FIRST_SEGMENTS = Set.of("actuator", "eureka");
    private static final int MAX_DECODE_ROUNDS = 4;

    /**
     * Actuator chạy trên cổng riêng (management.server.port) và không được publish: chỉ các container trong mạng compose
     * truy cập được. Request thật sự đến cổng này được đọc /actuator/**; mọi thứ trên cổng công khai vẫn bị chặn.
     */
    @Value("${management.server.port:0}")
    private int managementPort;

    /**
     * Chặn request tới đường dẫn nội bộ hoặc quản trị bằng HTTP 404; riêng {@code /actuator} trên cổng management thì cho qua.
     *
     * @param exchange request và response hiện tại
     * @param chain phần còn lại của chuỗi bộ lọc
     * @return {@code Mono} hoàn tất khi đã xử lý xong request
     */
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

    /**
     * Kiểm tra request có thật sự đến từ cổng management hay không.
     *
     * @param local địa chỉ cục bộ mà request đã kết nối vào, có thể null
     * @param managementPort cổng management đã cấu hình, 0 nghĩa là không dùng
     * @return {@code true} khi cổng khớp và lớn hơn 0
     */
    static boolean arrivedOnManagementPort(java.net.InetSocketAddress local, int managementPort) {
        return local != null && managementPort > 0 && local.getPort() == managementPort;
    }

    /** Chỉ nhận đường dẫn /actuator hoặc /actuator/... thuần túy, không có mã hóa hay dấu chấm lắt léo (các trường hợp đó vẫn đi qua isBlocked). */
    static boolean isActuatorPath(String rawPath) {
        return rawPath != null && (rawPath.equals("/actuator") || rawPath.startsWith("/actuator/")) && !rawPath.contains("%")
                && !rawPath.contains("..") && !rawPath.contains("\\") && !rawPath.contains(";");
    }

    /**
     * Chuẩn hóa đường dẫn rồi quyết định có chặn hay không: chặn khi có đoạn {@code internal} ở bất kỳ độ sâu nào,
     * khi đoạn đầu là {@code actuator} hoặc {@code eureka}, hoặc khi mã hóa phần trăm bị lỗi.
     *
     * @param rawPath đường dẫn thô, chưa giải mã
     * @return {@code true} nếu phải chặn
     */
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

    /**
     * Giải mã phần trăm lặp lại (tối đa {@code MAX_DECODE_ROUNDS} lượt) để chặn kiểu mã hóa lồng nhau. Dấu cộng được giữ nguyên.
     *
     * @param path đường dẫn cần giải mã
     * @return đường dẫn sau khi giải mã, dừng khi không còn thay đổi
     * @throws IllegalArgumentException khi chuỗi mã hóa phần trăm sai định dạng
     */
    private static String decodeFully(String path) {
        String current = path;
        for (int round = 0; round < MAX_DECODE_ROUNDS; round++) {
            String next = URLDecoder.decode(current.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (next.equals(current)) return current;
            current = next;
        }
        return current;
    }

    /**
     * Tách đường dẫn thành các đoạn: đổi dấu gạch ngược thành gạch chéo, bỏ tham số matrix sau dấu chấm phẩy, cắt khoảng trắng, đưa về chữ thường và bỏ đoạn rỗng.
     *
     * @param path đường dẫn đã giải mã
     * @return danh sách đoạn đã chuẩn hóa
     */
    private static List<String> segments(String path) {
        return java.util.Arrays.stream(path.replace('\\', '/').split("/"))
                .map(segment -> {
                    int matrix = segment.indexOf(';');
                    return (matrix >= 0 ? segment.substring(0, matrix) : segment).strip().toLowerCase(Locale.ROOT);
                })
                .filter(segment -> !segment.isEmpty())
                .toList();
    }

    /**
     * Xử lý các đoạn {@code .} (bỏ qua) và {@code ..} (lùi một cấp) như khi hệ thống tệp phân giải đường dẫn.
     *
     * @param segments các đoạn đã chuẩn hóa
     * @return danh sách đoạn sau khi phân giải
     */
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

    /**
     * Làm sạch chuỗi trước khi ghi log: thay ký tự điều khiển bằng dấu hỏi và cắt còn tối đa 200 ký tự.
     *
     * @param value chuỗi cần ghi log
     * @return chuỗi an toàn để ghi log
     */
    private static String printable(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "?");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }
}
