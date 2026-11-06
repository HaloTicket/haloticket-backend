package com.haloticket.event_service.client;

import com.haloticket.event_service.exception.TicketSyncRejectedException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TicketServiceErrorDecoderTest {
    private final TicketServiceErrorDecoder decoder = new TicketServiceErrorDecoder();

    private static Response response(int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST, "http://ticket-service/x", Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder().status(status).reason("r").request(request).headers(Map.of())
                .body(body, StandardCharsets.UTF_8).build();
    }

    @Test
    void a422BecomesTheDomainExceptionWithTheDetails() {
        Exception decoded = decoder.decode("sync", response(422,
                "{\"code\":\"QUANTITY_BELOW_SOLD_AND_HELD\",\"ticketTypeId\":\"tt1\",\"name\":\"VIP\",\"sold\":30,\"held\":5,\"minAllowed\":35,\"extra\":\"x\"}"));

        assertThat(decoded).isInstanceOfSatisfying(TicketSyncRejectedException.class, e -> {
            assertThat(e.getCode()).isEqualTo("QUANTITY_BELOW_SOLD_AND_HELD");
            assertThat(e.getDetails()).containsEntry("ticketTypeId", "tt1").containsEntry("name", "VIP")
                    .containsEntry("sold", 30).containsEntry("held", 5).containsEntry("minAllowed", 35).doesNotContainKey("extra");
        });
    }

    @Test
    void anUnreadable422BodyStillMapsToTheDomainException() {
        Exception decoded = decoder.decode("sync", response(422, "not json"));
        assertThat(decoded).isInstanceOf(TicketSyncRejectedException.class);
    }

    @Test
    void otherStatusesStayFeignExceptions() {
        assertThat(decoder.decode("sync", response(503, "{}"))).isInstanceOf(feign.FeignException.class);
        assertThat(decoder.decode("sync", response(400, "{}"))).isInstanceOf(feign.FeignException.class);
    }
}
