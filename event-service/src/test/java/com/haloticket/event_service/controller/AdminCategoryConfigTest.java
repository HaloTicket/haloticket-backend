package com.haloticket.event_service.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.config.DataInitializer;
import com.haloticket.event_service.config.DatabaseSchemaInitializer;
import com.haloticket.event_service.config.JwtRoleConverter;
import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.repository.EventCategoryRepository;
import com.haloticket.event_service.repository.EventSectionConfigRepository;
import com.haloticket.event_service.repository.HomepageBannerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * What the platform admin's category and homepage endpoints do (H2, real security chain): duplicates are refused, the order is
 * saved in one call and read back sorted, and a refused banner list leaves the stored one untouched.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:categoryconfig;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
@AutoConfigureMockMvc
class AdminCategoryConfigTest {

    @Autowired MockMvc mvc;
    @Autowired JwtRoleConverter converter;
    @Autowired EventCategoryRepository categories;
    @Autowired HomepageBannerRepository banners;
    @Autowired EventSectionConfigRepository sections;
    private final ObjectMapper json = new ObjectMapper();

    @MockitoBean DataInitializer dataInitializer;
    @MockitoBean DatabaseSchemaInitializer schemaInitializer;
    @MockitoBean UserServiceClient users;

    @BeforeEach
    void clean() {
        categories.deleteAll();
        banners.deleteAll();
        sections.deleteAll();
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        Jwt jwt = Jwt.withTokenValue("kc-root").header("alg", "none").subject("kc-root").claim("roles", List.of("ROLE_USER", "ROLE_ADMIN")).build();
        return request.with(authentication(converter.convert(jwt))).header("Authorization", "kc-root");
    }

    private int send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(asAdmin(request)).andReturn().getResponse().getStatus();
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(asAdmin(request)).andReturn().getResponse().getContentAsString());
    }

    private String createCategory(String name) throws Exception {
        JsonNode created = body(post("/api/events/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"));
        return created.get("id").asText();
    }

    @Test
    void aCategoryNeedsANameAndTheNameAndSlugStayUnique() throws Exception {
        assertThat(send(post("/api/events/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))).isEqualTo(400);
        assertThat(send(post("/api/events/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Music\",\"slug\":\"music\"}"))).isEqualTo(200);
        assertThat(send(post("/api/events/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"music\"}"))).as("same name, other case").isEqualTo(409);
        assertThat(send(post("/api/events/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Live\",\"slug\":\"MUSIC\"}"))).as("same slug").isEqualTo(409);
        String live = createCategory("Live");
        assertThat(send(put("/api/events/categories/" + live).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Music\"}"))).as("rename onto another").isEqualTo(409);
        assertThat(send(put("/api/events/categories/" + live).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Live\",\"icon\":\"mic\"}"))).as("keep own name").isEqualTo(200);
    }

    @Test
    void anUpdateKeepsTheFieldsItDoesNotSend() throws Exception {
        String id = body(post("/api/events/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Art\",\"description\":\"Galleries\",\"icon\":\"brush\"}")).get("id").asText();
        JsonNode updated = body(put("/api/events/categories/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"imageUrl\":\"https://x/y.png\"}"));
        assertThat(updated.get("name").asText()).isEqualTo("Art");
        assertThat(updated.get("description").asText()).isEqualTo("Galleries");
        assertThat(updated.get("icon").asText()).isEqualTo("brush");
        assertThat(updated.get("imageUrl").asText()).isEqualTo("https://x/y.png");
    }

    @Test
    void theOrderIsSavedInOneCallAndTheListsComeBackSorted() throws Exception {
        String a = createCategory("A");
        String b = createCategory("B");
        String c = createCategory("C");
        assertThat(names(body(get("/api/events/categories/admin")))).containsExactly("A", "B", "C");
        assertThat(send(put("/api/events/categories/order").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(List.of(c, a))))).isEqualTo(200);
        assertThat(names(body(get("/api/events/categories/admin")))).as("listed ones first, the rest after").containsExactly("C", "A", "B");
        assertThat(names(body(get("/api/events/categories")))).as("the public list follows the same order").containsExactly("C", "A", "B");
        assertThat(send(put("/api/events/categories/order").contentType(MediaType.APPLICATION_JSON).content("[\"" + a + "\",\"nope\"]"))).as("unknown id").isEqualTo(400);
        assertThat(names(body(get("/api/events/categories/admin")))).as("a refused order changes nothing").containsExactly("C", "A", "B");
        assertThat(b).isNotBlank();
    }

    @Test
    void aRefusedBannerListKeepsTheStoredOne() throws Exception {
        String good = "[{\"id\":\"b1\",\"title\":\"Summer\",\"imageUrl\":\"https://x/a.png\",\"linkUrl\":\"/events\",\"active\":true}]";
        assertThat(send(put("/api/events/admin/config/homepage/banners").contentType(MediaType.APPLICATION_JSON).content(good))).isEqualTo(200);
        String noTitle = "[{\"id\":\"b2\",\"title\":\" \",\"imageUrl\":\"https://x/a.png\"}]";
        assertThat(send(put("/api/events/admin/config/homepage/banners").contentType(MediaType.APPLICATION_JSON).content(noTitle))).isEqualTo(400);
        String badLink = "[{\"id\":\"b3\",\"title\":\"X\",\"linkUrl\":\"javascript:alert(1)\"}]";
        assertThat(send(put("/api/events/admin/config/homepage/banners").contentType(MediaType.APPLICATION_JSON).content(badLink))).isEqualTo(400);
        assertThat(banners.findAll()).extracting("id").containsExactly("b1");
        // saving the same list again replaces the rows with the same ids
        assertThat(send(put("/api/events/admin/config/homepage/banners").contentType(MediaType.APPLICATION_JSON).content(good))).isEqualTo(200);
        assertThat(banners.findAll()).hasSize(1);
    }

    @Test
    void theSystemConfigurationEndpointsAreGone() throws Exception {
        assertThat(send(get("/api/events/admin/config/system-config"))).isIn(404, 405, 500);
        assertThat(send(put("/api/events/admin/config/system-config").contentType(MediaType.APPLICATION_JSON).content("{}"))).isIn(404, 405, 500);
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.get("name").asText()));
        return names;
    }
}
