package com.haloticket.event_service.controller;

import com.haloticket.event_service.service.AdminEventReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
@AutoConfigureMockMvc
class AdminEventReportSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminEventReportService reports;

    @Test
    void rejectsNonAdminForAggregateReport() throws Exception {
        mvc.perform(get("/api/events/admin/reports").with(SecurityMockMvcRequestPostProcessors.user("organizer").roles("ORGANIZER"))
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isForbidden());
    }

    @Test
    void permitsAdminThroughTheAdminMatcher() throws Exception {
        mvc.perform(get("/api/events/admin/reports").with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
                        .header("Authorization", "test-token")
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAnonymousForAggregateReport() throws Exception {
        mvc.perform(get("/api/events/admin/reports").param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exposesActionableUnprocessableReportReason() throws Exception {
        when(reports.report(eq("test-token"), eq(LocalDate.parse("2026-09-01")), eq(LocalDate.parse("2026-09-30")),
                isNull(), isNull(), isNull(), isNull(), eq("revenue"), eq(1), eq(10), isNull(), eq(false)))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Có quá nhiều đơn thanh toán trong kỳ để tổng hợp. Vui lòng thu hẹp khoảng thời gian."));

        mvc.perform(get("/api/events/admin/reports").with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
                        .header("Authorization", "test-token")
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Có quá nhiều đơn thanh toán trong kỳ để tổng hợp. Vui lòng thu hẹp khoảng thời gian."));
    }
}
