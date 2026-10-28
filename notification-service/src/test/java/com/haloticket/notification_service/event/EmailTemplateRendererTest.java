package com.haloticket.notification_service.event;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Every e-mail template: whatever a person typed into a variable cannot become markup, unless the variable is declared HTML. */
class EmailTemplateRendererTest {

    private static final String HOSTILE = "<script>alert(1)</script><img src=x onerror=alert(2)><a href=\"https://evil.example/p\">pay</a>'\"{{otp}}";
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void everyVariableOfEveryTemplateIsEscapedUnlessItIsDeclaredAsHtml() throws Exception {
        Resource[] templates = new PathMatchingResourcePatternResolver().getResources("classpath:templates/*.html");
        assertThat(templates).hasSizeGreaterThanOrEqualTo(8);
        for (Resource template : templates) {
            String name = template.getFilename();
            String source = template.getContentAsString(StandardCharsets.UTF_8);
            Set<String> variables = new TreeSet<>();
            Matcher matcher = VARIABLE.matcher(source);
            while (matcher.find()) variables.add(matcher.group(1));
            assertThat(variables).as(name).isNotEmpty();

            Set<String> asHtml = EmailTemplateRenderer.HTML_VARIABLES.getOrDefault(name, Set.of());
            Map<String, String> values = new HashMap<>();
            for (String variable : variables) values.put(variable, asHtml.contains(variable) ? "<b>declared</b>" : HOSTILE);
            String html = renderer.render(name, values);

            assertThat(html).as(name + " has no markup from a person's text")
                    .doesNotContain("<script>alert").doesNotContain("<img src=x").doesNotContain("href=\"https://evil.example")
                    .doesNotContain("onerror=alert(2)>").doesNotContain("{{");
            if (!asHtml.isEmpty()) assertThat(html).as(name + " keeps its declared HTML").contains("<b>declared</b>");
            // the text is still there, as text
            assertThat(html).as(name).contains("&lt;script&gt;alert(1)&lt;/script&gt;".substring(0, 12));
        }
    }

    @Test
    void aNullValueIsEmptyAndNothingIsEscapedTwice() {
        Map<String, String> values = new HashMap<>();
        values.put("fullName", null);
        assertThat(renderer.render("welcome.html", values)).doesNotContain("null").doesNotContain("{{fullName}}");
        assertThat(renderer.render("welcome.html", Map.of("fullName", "Tom & Jerry"))).contains("Tom &amp; Jerry").doesNotContain("&amp;amp;");
    }

    @Test
    void theDeclaredHtmlVariablesAreTheOnesTheCallersBuildFromConstantsAndEscapedParts() {
        assertThat(EmailTemplateRenderer.HTML_VARIABLES).containsOnlyKeys("order-confirmation.html", "gift-invitation.html", "event-member-invitation.html");
        assertThat(EmailTemplateRenderer.HTML_VARIABLES.get("order-confirmation.html")).containsExactly("ticketRows");
        assertThat(EmailTemplateRenderer.HTML_VARIABLES.get("gift-invitation.html")).containsExactly("messageBlock");
        assertThat(EmailTemplateRenderer.HTML_VARIABLES.get("event-member-invitation.html")).containsExactlyInAnyOrder("newUserContent", "setPasswordBlock");
    }
}
