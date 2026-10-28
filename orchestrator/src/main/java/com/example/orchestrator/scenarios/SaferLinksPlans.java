package com.example.orchestrator.scenarios;

import java.util.List;

/**
 * Ambiguous: "safer" could mean several different features. The requirements
 * agent detects this, the run pauses for a product owner to choose, and only the
 * chosen interpretation is planned. Choosing an option with no reviewed plan
 * (e.g. interstitial-warning) is blocked and handed off to a human.
 */
final class SaferLinksPlans {

    private static final String PROPERTIES = "src/main/java/com/example/shorturl/config/AppProperties.java";
    private static final String VALIDATOR = "src/main/java/com/example/shorturl/service/UrlValidator.java";

    private SaferLinksPlans() {
    }

    static ChangePlan domainBlocklist() {
        return ChangePlan.builder("domain-blocklist", "Configurable blocklist of destination domains")
                .matches((spec, clarification) -> clarification != null && "domain-blocklist".equals(clarification.choice()))
                .approach("Bind a comma-separated BLOCKED_DOMAINS setting to a new AppProperties.blockedDomains list (trimmed and "
                        + "lower-cased), and reject URLs whose host is a blocked domain or a subdomain of one in UrlValidator, next to the "
                        + "existing protocol and self-reference checks. Matching is on whole domain labels, so \"notevil.com\" is not blocked "
                        + "by \"evil.com\". The existing two-argument AppProperties constructor is kept so other code and tests are unchanged.")
                .refinedCriterion("Shortening a URL whose domain, or a subdomain of it, is listed in BLOCKED_DOMAINS is rejected with 400 URL_DOMAIN_BLOCKED.")
                .refinedCriterion("Similar but different domains (e.g. notevil.com when evil.com is blocked) are not blocked.")
                .refinedCriterion("The blocklist is configured with the BLOCKED_DOMAINS environment variable and documented in the README.")
                .alternative("Check every URL against a threat-intelligence API: better coverage, but adds a network dependency and cost per request")
                .alternative("Block at redirect time instead of creation time: catches newly-listed domains, but every redirect pays the cost")
                .task("T1", "AppProperties + application.yml: bind BLOCKED_DOMAINS", List.of("blocked_domains", "configured"))
                .task("T2", "UrlValidator: reject blocked domains and subdomains only", List.of("rejected", "not blocked", "subdomain", "safer"), "T1")
                .task("T3", "Unit + MockMvc tests", List.of("400", "not blocked"), "T2")
                .task("T4", "README configuration row and error code", List.of("readme", "documented"), "T1")
                .risk("A blocklist only stops known-bad domains; it does not detect new phishing sites")
                .risk("Links created before a domain was blocked keep redirecting (blocking happens at creation)")
                .risk("An operator typo in BLOCKED_DOMAINS silently blocks nothing; values are trimmed and lower-cased to reduce this")
                .assumption("Operations maintains the list; no admin UI is needed yet")
                .assumption("Blocking at creation time is enough for this complaint")
                .rollbackPlan("Revert the release, or set BLOCKED_DOMAINS to empty to disable the check without a deploy.")
                .docsMustMention("README.md", "URL_DOMAIN_BLOCKED", "new error code")

                // ---- T1: configuration
                .replace(PROPERTIES, """
                        import java.net.URI;

                        import org.springframework.boot.context.properties.ConfigurationProperties;
                        """, """
                        import java.net.URI;
                        import java.util.List;
                        import java.util.Locale;

                        import org.springframework.boot.context.properties.ConfigurationProperties;
                        import org.springframework.boot.context.properties.bind.ConstructorBinding;
                        """)
                .replace(PROPERTIES, """
                         * @param createRateLimit link-creation requests allowed per client IP per 15 minutes
                         */
                        @ConfigurationProperties(prefix = "app")
                        public record AppProperties(String baseUrl, int createRateLimit) {

                            public AppProperties {
                                baseUrl = baseUrl.replaceAll("/+$", "");
                            }
                        """, """
                         * @param createRateLimit link-creation requests allowed per client IP per 15 minutes
                         * @param blockedDomains  domains that may not be shortened (their subdomains are blocked too)
                         */
                        @ConfigurationProperties(prefix = "app")
                        public record AppProperties(String baseUrl, int createRateLimit, List<String> blockedDomains) {

                            @ConstructorBinding
                            public AppProperties {
                                baseUrl = baseUrl.replaceAll("/+$", "");
                                blockedDomains = blockedDomains == null ? List.of()
                                        : blockedDomains.stream().map(domain -> domain.trim().toLowerCase(Locale.ROOT)).filter(domain -> !domain.isEmpty()).toList();
                            }

                            public AppProperties(String baseUrl, int createRateLimit) {
                                this(baseUrl, createRateLimit, List.of());
                            }
                        """)
                .replace("src/main/resources/application.yml", """
                          create-rate-limit: ${CREATE_RATE_LIMIT:100}
                        """, """
                          create-rate-limit: ${CREATE_RATE_LIMIT:100}
                          blocked-domains: ${BLOCKED_DOMAINS:}   # comma-separated, e.g. evil.com,phish.example
                        """)

                // ---- T2: validation
                .replace(VALIDATOR, """
                        import java.util.Set;
                        """, """
                        import java.util.List;
                        import java.util.Locale;
                        import java.util.Set;
                        """)
                .replace(VALIDATOR, """
                         *  - must not point back at this service (prevents redirect loops)
                        """, """
                         *  - must not point back at this service (prevents redirect loops)
                         *  - must not be on a blocked domain, or a subdomain of one
                        """)
                .replace(VALIDATOR, """
                            private final String ownHost;

                            public UrlValidator(AppProperties properties) {
                                this.ownHost = properties.host();
                            }
                        """, """
                            private final String ownHost;
                            private final List<String> blockedDomains;

                            public UrlValidator(AppProperties properties) {
                                this.ownHost = properties.host();
                                this.blockedDomains = properties.blockedDomains();
                            }
                        """)
                .replace(VALIDATOR, """
                                    throw ShortUrlException.badRequest("URL_SELF_REFERENCE", "Links to this service cannot be shortened.");
                                }
                        """, """
                                    throw ShortUrlException.badRequest("URL_SELF_REFERENCE", "Links to this service cannot be shortened.");
                                }

                                String host = uri.getHost().toLowerCase(Locale.ROOT);
                                if (blockedDomains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain))) {
                                    throw ShortUrlException.badRequest("URL_DOMAIN_BLOCKED", "Links to this domain are not allowed.");
                                }
                        """)

                // ---- T3: tests
                .create("src/test/java/com/example/shorturl/service/BlockedDomainsTest.java", """
                        package com.example.shorturl.service;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import static org.assertj.core.api.Assertions.assertThatThrownBy;

                        import java.util.List;

                        import org.junit.jupiter.params.ParameterizedTest;
                        import org.junit.jupiter.params.provider.ValueSource;
                        import org.junit.jupiter.api.Test;

                        import com.example.shorturl.config.AppProperties;

                        class BlockedDomainsTest {

                            private final UrlValidator validator =
                                    new UrlValidator(new AppProperties("http://short.test", 100, List.of(" Evil.com ", "phish.example", "")));

                            @ParameterizedTest
                            @ValueSource(strings = {"https://evil.com/login", "https://www.evil.com/", "http://a.b.phish.example/x", "https://EVIL.COM/upper"})
                            void rejectsBlockedDomainsAndTheirSubdomains(String url) {
                                assertThatThrownBy(() -> validator.normalize(url)).hasFieldOrPropertyWithValue("code", "URL_DOMAIN_BLOCKED");
                            }

                            @ParameterizedTest
                            @ValueSource(strings = {"https://notevil.com/", "https://evil.com.example.org/"})
                            void allowsSimilarButDifferentDomains(String url) {
                                assertThat(validator.normalize(url)).isEqualTo(url);
                            }

                            @Test
                            void anEmptyBlocklistBlocksNothing() {
                                UrlValidator open = new UrlValidator(new AppProperties("http://short.test", 100));
                                assertThat(open.normalize("https://evil.com/")).isEqualTo("https://evil.com/");
                            }
                        }
                        """)
                .create("src/test/java/com/example/shorturl/web/BlockedDomainsWebTest.java", """
                        package com.example.shorturl.web;

                        import static org.hamcrest.Matchers.containsString;
                        import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
                        import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
                        import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
                        import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                        import org.junit.jupiter.api.Test;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.boot.test.context.SpringBootTest;
                        import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
                        import org.springframework.test.context.ActiveProfiles;
                        import org.springframework.test.web.servlet.MockMvc;

                        @SpringBootTest(properties = "app.blocked-domains=evil.com")
                        @AutoConfigureMockMvc
                        @ActiveProfiles("test")
                        class BlockedDomainsWebTest {

                            @Autowired
                            private MockMvc mvc;

                            @Test
                            void creatingALinkToABlockedDomainIsRejected() throws Exception {
                                mvc.perform(post("/shorten").with(csrf()).param("url", "https://login.evil.com/account"))
                                        .andExpect(status().isBadRequest())
                                        .andExpect(content().string(containsString("Links to this domain are not allowed.")));
                            }

                            @Test
                            void otherDomainsAreStillAccepted() throws Exception {
                                mvc.perform(post("/shorten").with(csrf()).param("url", "https://notevil.com/")).andExpect(status().isFound());
                            }
                        }
                        """)

                // ---- T4: README
                .replace("README.md", """
                        | `CREATE_RATE_LIMIT` | `100` | Link-creation requests allowed per client IP per 15 minutes |
                        """, """
                        | `CREATE_RATE_LIMIT` | `100` | Link-creation requests allowed per client IP per 15 minutes |
                        | `BLOCKED_DOMAINS` | empty | Comma-separated domains that may not be shortened; subdomains are blocked too |
                        """)
                .replace("README.md", "non-http(s) or self-referencing URL; invalid or reserved alias |",
                        "non-http(s) or self-referencing URL; blocked domain; invalid or reserved alias |")
                .replace("README.md", "`URL_SELF_REFERENCE`, `ALIAS_INVALID`", "`URL_SELF_REFERENCE`, `URL_DOMAIN_BLOCKED`, `ALIAS_INVALID`")
                .build();
    }
}
