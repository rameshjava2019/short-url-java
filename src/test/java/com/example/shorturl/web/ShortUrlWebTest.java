package com.example.shorturl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.shorturl.model.ShortUrl;
import com.example.shorturl.repository.ShortUrlRepository;
import com.example.shorturl.service.ShortUrlView;

/**
 * End-to-end tests through HTTP (MockMvc) against a real, embedded MongoDB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShortUrlWebTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ShortUrlRepository repository;

    @BeforeEach
    void clearDatabase() {
        repository.deleteAll();
    }

    private ShortUrlView createViaUi(String url, String alias) throws Exception {
        MvcResult result = mvc.perform(post("/shorten").with(csrf()).param("url", url).param("alias", alias))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        return (ShortUrlView) result.getFlashMap().get("created");
    }

    @Test
    void homePageShowsTheFormWithACsrfToken() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("action=\"/shorten\"")));
    }

    @Test
    void creatingALinkWithoutACsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/shorten").param("url", "https://example.com")).andExpect(status().isForbidden());
        assertThat(repository.count()).isZero();
    }

    @Test
    void creatingALinkWithAnInvalidCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/shorten").with(csrf().useInvalidToken()).param("url", "https://example.com"))
                .andExpect(status().isForbidden());
        assertThat(repository.count()).isZero();
    }

    @Test
    void createsAShortLinkWithAValidCsrfToken() throws Exception {
        ShortUrlView created = createViaUi("https://example.com/some/long/path", "");

        assertThat(created.code()).matches("[A-Za-z0-9]{7}");
        assertThat(created.shortUrl()).isEqualTo("http://short.test/" + created.code());
        assertThat(created.originalUrl()).isEqualTo("https://example.com/some/long/path");
        assertThat(repository.findByCode(created.code())).isPresent();
    }

    @Test
    void theCreatedLinkAppearsInRecentLinksForThatSession() throws Exception {
        MvcResult result = mvc.perform(post("/shorten").with(csrf()).param("url", "https://example.com/recent"))
                .andExpect(flash().attributeExists("created"))
                .andReturn();

        mvc.perform(get("/").session((MockHttpSession) result.getRequest().getSession()))
                .andExpect(content().string(containsString("Your recent links")))
                .andExpect(content().string(containsString("https://example.com/recent")));
    }

    @Test
    void aCustomAliasIsUsedAndDuplicatesAreRejected() throws Exception {
        assertThat(createViaUi("https://example.com", "my-promo").code()).isEqualTo("my-promo");

        mvc.perform(post("/shorten").with(csrf()).param("url", "https://example.org").param("alias", "my-promo"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("already taken")));
    }

    @Test
    void invalidInputShowsAHelpfulError() throws Exception {
        mvc.perform(post("/shorten").with(csrf()).param("url", ""))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Please provide a URL.")));
        mvc.perform(post("/shorten").with(csrf()).param("url", "javascript:alert(1)"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Only http:// and https:// URLs are allowed.")));
        mvc.perform(post("/shorten").with(csrf()).param("url", "http://short.test/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Links to this service cannot be shortened.")));
        mvc.perform(post("/shorten").with(csrf()).param("url", "https://example.com").param("alias", "api"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("is reserved")));
    }

    @Test
    void publicShortLinkRedirectsWithoutTokenOrSessionAndCountsClicks() throws Exception {
        ShortUrlView created = createViaUi("https://example.com/target", "");

        // A brand-new client with no session, like someone clicking the link in an email.
        MvcResult first = mvc.perform(get("/" + created.code()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target"))
                .andReturn();
        assertThat(first.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(first.getRequest().getSession(false)).isNull();

        mvc.perform(get("/" + created.code())).andExpect(status().isFound());

        mvc.perform(get("/api/urls/" + created.code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(2))
                .andExpect(jsonPath("$.lastAccessedAt").exists());
    }

    @Test
    void unknownShortCodesReturnA404Page() throws Exception {
        mvc.perform(get("/doesNotExist"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Link not found")));
    }

    @Test
    void statsForAnUnknownCodeReturn404Json() throws Exception {
        mvc.perform(get("/api/urls/nope123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void healthCheckAndSecurityHeaders() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void storedDocumentsHaveAUniqueCode() {
        repository.insert(new ShortUrl("dupe123", "https://a.example", true));
        assertThatThrownBy(() -> repository.insert(new ShortUrl("dupe123", "https://b.example", true)))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
