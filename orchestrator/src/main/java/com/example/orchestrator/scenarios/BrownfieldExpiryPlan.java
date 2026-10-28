package com.example.orchestrator.scenarios;

import java.util.List;

/**
 * Brownfield: change existing classes across every layer (document, service,
 * controllers, form, template) without breaking existing data or behaviour.
 */
final class BrownfieldExpiryPlan {

    private static final String MODEL = "src/main/java/com/example/shorturl/model/ShortUrl.java";
    private static final String VIEW = "src/main/java/com/example/shorturl/service/ShortUrlView.java";
    private static final String SERVICE = "src/main/java/com/example/shorturl/service/ShortUrlService.java";
    private static final String REDIRECT = "src/main/java/com/example/shorturl/web/RedirectController.java";
    private static final String FORM = "src/main/java/com/example/shorturl/web/CreateLinkForm.java";
    private static final String HOME = "src/main/java/com/example/shorturl/web/HomeController.java";
    private static final String INDEX = "src/main/resources/templates/index.html";

    private BrownfieldExpiryPlan() {
    }

    static ChangePlan create() {
        return ChangePlan.builder("link-expiry", "Optional expiresAt on links, 410 for expired links")
                .approach("Add an optional expiresAt field to the ShortUrl document (null = never). The service converts expiresInDays "
                        + "into an Instant and validates the range. The redirect keeps its single atomic findAndModify, now limited to "
                        + "non-expired links; only when that misses does it run one extra exists() query to tell \"expired\" (410) from "
                        + "\"unknown\" (404). The result is a small sealed Resolution type instead of Optional<String>. Expired visits are "
                        + "not counted as clicks.")
                .alternative("MongoDB TTL index that deletes expired links: rejected, the alias would be freed for someone else and stats lost")
                .alternative("Read the link, then update it (two queries on every redirect): rejected, slower hot path and not atomic")
                .task("T1", "ShortUrl: optional expiresAt (null = never)", List.of("expiresat", "existing links", "never expire"))
                .task("T2", "ShortUrlService: validate expiresInDays (1-365), store expiresAt", List.of("expiresindays", "1 and 365"), "T1")
                .task("T3", "Redirect: 410 Gone for expired links, 404 for unknown", List.of("410", "expired"), "T2")
                .task("T4", "Form + template: expiry selector", List.of("form", "expiresindays"), "T2")
                .task("T5", "MockMvc tests: expiry, validation, documents without the new field", List.of("existing links", "410"), "T3")
                .task("T6", "README: new field and 410 response", List.of("readme", "document"), "T3")
                .risk("Server clock skew could expire links slightly early or late; acceptable at day granularity")
                .risk("Expired visits are not counted, so clickCount means \"successful redirects\"")
                .risk("Callers of the two-argument create() keep the previous behaviour (never expire)")
                .assumption("Expiry is set only at creation; editing it later is out of scope")
                .assumption("410 is acceptable to the marketing team (vs. a friendly landing page)")
                .rollbackPlan("Revert the release. The new field is optional and the previous code ignores it, so links created with an "
                        + "expiry would simply stop expiring; no data migration or cleanup is needed.")
                .docsMustMention("README.md", "expiresInDays", "new form field")
                .docsMustMention("README.md", "410", "new response status")

                // ---- T1: document
                .replace(MODEL, """
                            private Instant createdAt;

                            protected ShortUrl() {
                        """, """
                            private Instant createdAt;

                            /** Optional expiry; null means the link never expires. */
                            private Instant expiresAt;

                            protected ShortUrl() {
                        """)
                .replace(MODEL, """
                            public ShortUrl(String code, String originalUrl, boolean customAlias) {
                                this.code = code;
                                this.originalUrl = originalUrl;
                                this.customAlias = customAlias;
                                this.createdAt = Instant.now();
                            }
                        """, """
                            public ShortUrl(String code, String originalUrl, boolean customAlias) {
                                this(code, originalUrl, customAlias, null);
                            }

                            public ShortUrl(String code, String originalUrl, boolean customAlias, Instant expiresAt) {
                                this.code = code;
                                this.originalUrl = originalUrl;
                                this.customAlias = customAlias;
                                this.createdAt = Instant.now();
                                this.expiresAt = expiresAt;
                            }
                        """)
                .replace(MODEL, """
                            public Instant getCreatedAt() {
                                return createdAt;
                            }
                        }
                        """, """
                            public Instant getCreatedAt() {
                                return createdAt;
                            }

                            public Instant getExpiresAt() {
                                return expiresAt;
                            }
                        }
                        """)
                .replace(VIEW, """
                                Instant lastAccessedAt) implements Serializable {

                            public static ShortUrlView of(ShortUrl doc, String baseUrl) {
                                return new ShortUrlView(doc.getCode(), baseUrl + "/" + doc.getCode(), doc.getOriginalUrl(),
                                        doc.getClickCount(), doc.getCreatedAt(), doc.getLastAccessedAt());
                            }
                        """, """
                                Instant lastAccessedAt,
                                Instant expiresAt) implements Serializable {

                            public static ShortUrlView of(ShortUrl doc, String baseUrl) {
                                return new ShortUrlView(doc.getCode(), baseUrl + "/" + doc.getCode(), doc.getOriginalUrl(),
                                        doc.getClickCount(), doc.getCreatedAt(), doc.getLastAccessedAt(), doc.getExpiresAt());
                            }
                        """)

                // ---- T2 + T3: service
                .create("src/main/java/com/example/shorturl/service/Resolution.java", """
                        package com.example.shorturl.service;

                        /** The outcome of looking up a short code for a redirect. */
                        public sealed interface Resolution {

                            record Found(String url) implements Resolution {
                            }

                            record Expired() implements Resolution {
                            }

                            record NotFound() implements Resolution {
                            }
                        }
                        """)
                .replace(SERVICE, """
                        import java.time.Instant;
                        """, """
                        import java.time.Duration;
                        import java.time.Instant;
                        """)
                .replace(SERVICE, """
                            private static final int MAX_GENERATION_ATTEMPTS = 5;
                        """, """
                            private static final int MAX_GENERATION_ATTEMPTS = 5;
                            private static final int MAX_EXPIRY_DAYS = 365;
                        """)
                .replace(SERVICE, """
                            public ShortUrlView create(String url, String alias) {
                                String originalUrl = urlValidator.normalize(url);
                        """, """
                            public ShortUrlView create(String url, String alias) {
                                return create(url, alias, null);
                            }

                            /** Creates a link that stops working after {@code expiresInDays} days (null = never). */
                            public ShortUrlView create(String url, String alias, Integer expiresInDays) {
                                String originalUrl = urlValidator.normalize(url);
                                Instant expiresAt = resolveExpiry(expiresInDays);
                        """)
                .replace(SERVICE, "new ShortUrl(code, originalUrl, true)", "new ShortUrl(code, originalUrl, true, expiresAt)")
                .replace(SERVICE, "new ShortUrl(codeGenerator.generate(), originalUrl, false)",
                        "new ShortUrl(codeGenerator.generate(), originalUrl, false, expiresAt)")
                .replace(SERVICE, """
                            /**
                             * Looks up a code for redirecting and records the click in one atomic update.
                             * Returns the original URL, or empty if the code does not exist.
                             */
                            public Optional<String> resolveAndTrackClick(String code) {
                                if (!codeGenerator.isValidCodeFormat(code)) {
                                    return Optional.empty();
                                }
                                Query query = Query.query(Criteria.where("code").is(code));
                                Update update = new Update().inc("clickCount", 1).set("lastAccessedAt", Instant.now());
                                ShortUrl shortUrl = mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), ShortUrl.class);
                                return Optional.ofNullable(shortUrl).map(ShortUrl::getOriginalUrl);
                            }
                        """, """
                            /** Converts an optional "expires in N days" input into an instant, or null for "never". */
                            private static Instant resolveExpiry(Integer expiresInDays) {
                                if (expiresInDays == null) {
                                    return null;
                                }
                                if (expiresInDays < 1 || expiresInDays > MAX_EXPIRY_DAYS) {
                                    throw ShortUrlException.badRequest("EXPIRY_INVALID", "Expiry must be between 1 and " + MAX_EXPIRY_DAYS + " days.");
                                }
                                return Instant.now().plus(Duration.ofDays(expiresInDays));
                            }

                            /**
                             * Looks up a code for redirecting and records the click in one atomic update.
                             * Expired links are not counted as clicks.
                             */
                            public Resolution resolveAndTrackClick(String code) {
                                if (!codeGenerator.isValidCodeFormat(code)) {
                                    return new Resolution.NotFound();
                                }
                                Instant now = Instant.now();
                                Query query = Query.query(Criteria.where("code").is(code)
                                        .orOperator(Criteria.where("expiresAt").is(null), Criteria.where("expiresAt").gt(now)));
                                Update update = new Update().inc("clickCount", 1).set("lastAccessedAt", now);
                                ShortUrl shortUrl = mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), ShortUrl.class);
                                if (shortUrl != null) {
                                    return new Resolution.Found(shortUrl.getOriginalUrl());
                                }
                                // Missed: unknown or expired. One extra query, only on this rare path.
                                boolean expired = mongoTemplate.exists(Query.query(Criteria.where("code").is(code).and("expiresAt").lte(now)), ShortUrl.class);
                                return expired ? new Resolution.Expired() : new Resolution.NotFound();
                            }
                        """)
                .replace(REDIRECT, """
                        import com.example.shorturl.service.ShortUrlService;
                        """, """
                        import com.example.shorturl.service.Resolution;
                        import com.example.shorturl.service.ShortUrlService;
                        """)
                .replace(REDIRECT, """
                            public ModelAndView redirect(@PathVariable String code) {
                                return shortUrlService.resolveAndTrackClick(code)
                                        .map(RedirectController::redirectTo)
                                        .orElseGet(() -> new ModelAndView("not-found", HttpStatus.NOT_FOUND));
                            }
                        """, """
                            public ModelAndView redirect(@PathVariable String code) {
                                Resolution resolution = shortUrlService.resolveAndTrackClick(code);
                                if (resolution instanceof Resolution.Found found) {
                                    return redirectTo(found.url());
                                }
                                if (resolution instanceof Resolution.Expired) {
                                    return new ModelAndView("expired", HttpStatus.GONE);
                                }
                                return new ModelAndView("not-found", HttpStatus.NOT_FOUND);
                            }
                        """)
                .create("src/main/resources/templates/expired.html", """
                        <!DOCTYPE html>
                        <html lang="en" xmlns:th="http://www.thymeleaf.org">
                        <head>
                            <meta charset="utf-8">
                            <title>Link expired</title>
                            <link rel="stylesheet" th:href="@{/css/styles.css}">
                        </head>
                        <body>
                        <main class="card">
                            <h1>Link expired</h1>
                            <p>This short link has expired and no longer redirects.</p>
                            <p><a th:href="@{/}">Create a short link</a></p>
                        </main>
                        </body>
                        </html>
                        """)

                // ---- T4: form + template
                .replace(FORM, """
                            @Size(max = 30, message = "Alias must be at most 30 characters.")
                            private String alias;
                        """, """
                            @Size(max = 30, message = "Alias must be at most 30 characters.")
                            private String alias;

                            /** Optional: the link stops working after this many days. */
                            private Integer expiresInDays;
                        """)
                .replace(FORM, """
                            public void setAlias(String alias) {
                                this.alias = alias;
                            }
                        }
                        """, """
                            public void setAlias(String alias) {
                                this.alias = alias;
                            }

                            public Integer getExpiresInDays() {
                                return expiresInDays;
                            }

                            public void setExpiresInDays(Integer expiresInDays) {
                                this.expiresInDays = expiresInDays;
                            }
                        }
                        """)
                .replace(HOME, "shortUrlService.create(form.getUrl(), form.getAlias());",
                        "shortUrlService.create(form.getUrl(), form.getAlias(), form.getExpiresInDays());")
                .replace(INDEX, """
                                <button type="submit">Shorten</button>
                        """, """
                                <label for="expiresInDays">Expires <span class="optional">(optional)</span></label>
                                <select id="expiresInDays" th:field="*{expiresInDays}">
                                    <option value="">Never</option>
                                    <option value="1">After 1 day</option>
                                    <option value="7">After 7 days</option>
                                    <option value="30">After 30 days</option>
                                </select>

                                <button type="submit">Shorten</button>
                        """)
                .replace("src/main/resources/static/css/styles.css", "input {\n  width: 100%;", "input,\nselect {\n  width: 100%;")

                // ---- T5: tests
                .create("src/test/java/com/example/shorturl/web/ExpiryWebTest.java", """
                        package com.example.shorturl.web;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import static org.hamcrest.Matchers.containsString;
                        import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
                        import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
                        import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
                        import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
                        import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
                        import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                        import java.time.Duration;
                        import java.time.Instant;

                        import org.bson.Document;
                        import org.junit.jupiter.api.BeforeEach;
                        import org.junit.jupiter.api.Test;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.boot.test.context.SpringBootTest;
                        import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
                        import org.springframework.data.mongodb.core.MongoTemplate;
                        import org.springframework.data.mongodb.core.query.Criteria;
                        import org.springframework.data.mongodb.core.query.Query;
                        import org.springframework.data.mongodb.core.query.Update;
                        import org.springframework.test.context.ActiveProfiles;
                        import org.springframework.test.web.servlet.MockMvc;

                        import com.example.shorturl.model.ShortUrl;
                        import com.example.shorturl.repository.ShortUrlRepository;
                        import com.example.shorturl.service.ShortUrlView;

                        @SpringBootTest
                        @AutoConfigureMockMvc
                        @ActiveProfiles("test")
                        class ExpiryWebTest {

                            @Autowired
                            private MockMvc mvc;

                            @Autowired
                            private ShortUrlRepository repository;

                            @Autowired
                            private MongoTemplate mongoTemplate;

                            @BeforeEach
                            void clearDatabase() {
                                repository.deleteAll();
                            }

                            private ShortUrlView create(String url, String expiresInDays) throws Exception {
                                return (ShortUrlView) mvc.perform(post("/shorten").with(csrf()).param("url", url).param("expiresInDays", expiresInDays))
                                        .andExpect(status().isFound())
                                        .andReturn().getFlashMap().get("created");
                            }

                            @Test
                            void aLinkWithAnExpiryRedirectsUntilThen() throws Exception {
                                ShortUrlView created = create("https://example.com/campaign", "7");
                                Duration remaining = Duration.between(Instant.now(), created.expiresAt());
                                assertThat(remaining.toHours()).isBetween(167L, 168L);
                                mvc.perform(get("/" + created.code())).andExpect(status().isFound());
                            }

                            @Test
                            void anExpiredLinkReturns410AndIsNotCountedAsAClick() throws Exception {
                                ShortUrlView created = create("https://example.com/old", "1");
                                mongoTemplate.updateFirst(Query.query(Criteria.where("code").is(created.code())),
                                        new Update().set("expiresAt", Instant.now().minusSeconds(1)), ShortUrl.class);

                                mvc.perform(get("/" + created.code()))
                                        .andExpect(status().isGone())
                                        .andExpect(content().string(containsString("Link expired")));
                                mvc.perform(get("/api/urls/" + created.code())).andExpect(jsonPath("$.clickCount").value(0));
                            }

                            @Test
                            void linksWithoutAnExpiryNeverExpire() throws Exception {
                                ShortUrlView created = create("https://example.com/forever", "");
                                assertThat(created.expiresAt()).isNull();
                                mvc.perform(get("/" + created.code())).andExpect(status().isFound());
                            }

                            @Test
                            void documentsCreatedBeforeThisChangeKeepWorking() throws Exception {
                                mongoTemplate.getCollection("shorturls").insertOne(
                                        new Document("code", "legacy1").append("originalUrl", "https://example.com/legacy").append("clickCount", 0L));
                                mvc.perform(get("/legacy1")).andExpect(status().isFound());
                            }

                            @Test
                            void expiryOutsideTheAllowedRangeIsRejected() throws Exception {
                                for (String value : new String[] {"0", "366"}) {
                                    mvc.perform(post("/shorten").with(csrf()).param("url", "https://example.com").param("expiresInDays", value))
                                            .andExpect(status().isBadRequest())
                                            .andExpect(content().string(containsString("Expiry must be between 1 and 365 days.")));
                                }
                                assertThat(repository.count()).isZero();
                            }

                            @Test
                            void statsIncludeTheExpiryDate() throws Exception {
                                ShortUrlView created = create("https://example.com/stats", "30");
                                mvc.perform(get("/api/urls/" + created.code())).andExpect(jsonPath("$.expiresAt").exists());
                            }

                            @Test
                            void unknownCodesStillReturn404() throws Exception {
                                mvc.perform(get("/nothere1")).andExpect(status().isNotFound());
                            }
                        }
                        """)

                // ---- T6: README
                .replace("README.md", "Form fields: `url`, `alias` (optional) |",
                        "Form fields: `url`, `alias` (optional), `expiresInDays` (optional, 1-365) |")
                .replace("README.md", "| `GET` | `/{code}` | Public | no | `302` redirect to the original URL, or a `404` page |",
                        "| `GET` | `/{code}` | Public | no | `302` redirect to the original URL, `410 Gone` if the link has expired, or a `404` page |")
                .build();
    }
}
