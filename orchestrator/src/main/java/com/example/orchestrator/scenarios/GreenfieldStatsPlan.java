package com.example.orchestrator.scenarios;

import java.util.List;

/**
 * Greenfield: a new feature with a new service class and a new endpoint.
 */
final class GreenfieldStatsPlan {

    private static final String API_CONTROLLER = "src/main/java/com/example/shorturl/web/ApiController.java";

    private GreenfieldStatsPlan() {
    }

    static ChangePlan create() {
        return ChangePlan.builder("stats-endpoint", "Add GET /api/stats backed by a MongoDB aggregation")
                .approach("Create a StatsService that runs one $group aggregation through MongoTemplate (so no documents are loaded "
                        + "into memory) and expose it as a read-only GET in ApiController. GET requests change no state, and Spring "
                        + "Security only checks CSRF tokens on state-changing requests, so no token is needed and SecurityConfig is unchanged.")
                .alternative("Count in Java with findAll(): rejected, loads every document")
                .alternative("Keep running totals in a separate counter document: rejected for now, adds a second write per click")
                .task("T1", "StatsService: aggregate totals in MongoDB", List.of("aggregat", "memory", "total"))
                .task("T2", "GET /api/stats in ApiController (read-only, no CSRF)", List.of("csrf", "read-only", "/api/stats"), "T1")
                .task("T3", "MockMvc tests for the endpoint", List.of("returns", "json"), "T2")
                .task("T4", "Document the endpoint in the README", List.of("readme", "document"), "T2")
                .risk("The aggregation scans the whole collection; fine at current scale, needs a cached counter at millions of links")
                .risk("Totals are public; acceptable because they contain no per-link or personal data")
                .assumption("\"Total clicks\" means the sum of clickCount over all links")
                .assumption("No authentication is required, like the other read endpoints")
                .rollbackPlan("Revert the release: the change only adds a service and an endpoint, no data is migrated.")
                .docsMustMention("README.md", "/api/stats", "new public endpoint")
                .create("src/main/java/com/example/shorturl/service/StatsService.java", """
                        package com.example.shorturl.service;

                        import org.bson.Document;
                        import org.springframework.data.mongodb.core.MongoTemplate;
                        import org.springframework.data.mongodb.core.aggregation.Aggregation;
                        import org.springframework.stereotype.Service;

                        import com.example.shorturl.model.ShortUrl;

                        /**
                         * Usage statistics across all short URLs.
                         * Totals are computed inside MongoDB, so no documents are loaded into memory.
                         */
                        @Service
                        public class StatsService {

                            /** Totals across all links. */
                            public record UsageStats(long totalLinks, long totalClicks) {
                            }

                            private final MongoTemplate mongoTemplate;

                            public StatsService(MongoTemplate mongoTemplate) {
                                this.mongoTemplate = mongoTemplate;
                            }

                            public UsageStats totals() {
                                Aggregation aggregation = Aggregation.newAggregation(
                                        Aggregation.group().count().as("totalLinks").sum("clickCount").as("totalClicks"));
                                Document totals = mongoTemplate.aggregate(aggregation, ShortUrl.class, Document.class).getUniqueMappedResult();
                                if (totals == null) {
                                    return new UsageStats(0, 0);
                                }
                                return new UsageStats(((Number) totals.get("totalLinks")).longValue(), ((Number) totals.get("totalClicks")).longValue());
                            }
                        }
                        """)
                .replace(API_CONTROLLER, """
                        import com.example.shorturl.service.ShortUrlView;
                        """, """
                        import com.example.shorturl.service.ShortUrlView;
                        import com.example.shorturl.service.StatsService;
                        import com.example.shorturl.service.StatsService.UsageStats;
                        """)
                .replace(API_CONTROLLER, """
                            private final ShortUrlService shortUrlService;

                            public ApiController(ShortUrlService shortUrlService) {
                                this.shortUrlService = shortUrlService;
                            }
                        """, """
                            private final ShortUrlService shortUrlService;
                            private final StatsService statsService;

                            public ApiController(ShortUrlService shortUrlService, StatsService statsService) {
                                this.shortUrlService = shortUrlService;
                                this.statsService = statsService;
                            }
                        """)
                .replace(API_CONTROLLER, """
                            @GetMapping("/health")
                        """, """
                            /** Totals across all links, aggregated in MongoDB. */
                            @GetMapping("/api/stats")
                            public UsageStats usage() {
                                return statsService.totals();
                            }

                            @GetMapping("/health")
                        """)
                .create("src/test/java/com/example/shorturl/web/StatsWebTest.java", """
                        package com.example.shorturl.web;

                        import static org.assertj.core.api.Assertions.assertThat;
                        import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
                        import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
                        import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                        import org.junit.jupiter.api.BeforeEach;
                        import org.junit.jupiter.api.Test;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.boot.test.context.SpringBootTest;
                        import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
                        import org.springframework.test.context.ActiveProfiles;
                        import org.springframework.test.web.servlet.MockMvc;
                        import org.springframework.test.web.servlet.MvcResult;

                        import com.example.shorturl.model.ShortUrl;
                        import com.example.shorturl.repository.ShortUrlRepository;

                        @SpringBootTest
                        @AutoConfigureMockMvc
                        @ActiveProfiles("test")
                        class StatsWebTest {

                            @Autowired
                            private MockMvc mvc;

                            @Autowired
                            private ShortUrlRepository repository;

                            @BeforeEach
                            void clearDatabase() {
                                repository.deleteAll();
                            }

                            @Test
                            void returnsZerosWhenThereAreNoLinks() throws Exception {
                                mvc.perform(get("/api/stats"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.totalLinks").value(0))
                                        .andExpect(jsonPath("$.totalClicks").value(0));
                            }

                            @Test
                            void sumsLinksAndClicks() throws Exception {
                                repository.insert(new ShortUrl("aaa1111", "https://example.com/1", false));
                                repository.insert(new ShortUrl("bbb2222", "https://example.com/2", false));
                                for (int i = 0; i < 3; i++) mvc.perform(get("/aaa1111"));
                                for (int i = 0; i < 4; i++) mvc.perform(get("/bbb2222"));

                                mvc.perform(get("/api/stats"))
                                        .andExpect(jsonPath("$.totalLinks").value(2))
                                        .andExpect(jsonPath("$.totalClicks").value(7));
                            }

                            @Test
                            void needsNoCsrfTokenOrSession() throws Exception {
                                MvcResult result = mvc.perform(get("/api/stats")).andExpect(status().isOk()).andReturn();
                                assertThat(result.getRequest().getSession(false)).isNull();
                            }
                        }
                        """)
                .replace("README.md", """
                        | `GET` | `/api/urls/{code}` | Anyone | no | JSON stats for one link (click count etc.) |
                        """, """
                        | `GET` | `/api/urls/{code}` | Anyone | no | JSON stats for one link (click count etc.) |
                        | `GET` | `/api/stats` | Anyone | no | Totals across all links: `{ "totalLinks": 2, "totalClicks": 7 }` |
                        """)
                .build();
    }
}
