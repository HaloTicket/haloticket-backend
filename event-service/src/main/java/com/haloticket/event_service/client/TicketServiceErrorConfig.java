package com.haloticket.event_service.client;

import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/** Feign configuration of the ticket-service client: a 422 becomes a domain exception instead of a FeignException. */
public class TicketServiceErrorConfig {

    @Bean
    ErrorDecoder ticketServiceErrorDecoder() {
        return new TicketServiceErrorDecoder();
    }
}
