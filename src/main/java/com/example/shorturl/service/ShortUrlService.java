package com.example.shorturl.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.example.shorturl.config.AppProperties;
import com.example.shorturl.model.ShortUrl;
import com.example.shorturl.repository.ShortUrlRepository;

/** Business logic for short URLs. Controllers stay thin and call into this service. */
@Service
public class ShortUrlService {

    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final ShortUrlRepository repository;
    private final MongoTemplate mongoTemplate;
    private final UrlValidator urlValidator;
    private final ShortCodeGenerator codeGenerator;
    private final String baseUrl;

    public ShortUrlService(ShortUrlRepository repository, MongoTemplate mongoTemplate, UrlValidator urlValidator,
            ShortCodeGenerator codeGenerator, AppProperties properties) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.urlValidator = urlValidator;
        this.codeGenerator = codeGenerator;
        this.baseUrl = properties.baseUrl();
    }

    public ShortUrlView create(String url, String alias) {
        String originalUrl = urlValidator.normalize(url);

        if (alias != null && !alias.isBlank()) {
            String code = alias.trim();
            codeGenerator.validateAlias(code);
            try {
                return ShortUrlView.of(repository.insert(new ShortUrl(code, originalUrl, true)), baseUrl);
            } catch (DuplicateKeyException e) {
                throw new ShortUrlException(HttpStatus.CONFLICT, "ALIAS_TAKEN", "The alias \"" + code + "\" is already taken.");
            }
        }

        // Random codes can collide (very rarely). The unique index on "code"
        // detects it and we simply try again, a bounded number of times.
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                return ShortUrlView.of(repository.insert(new ShortUrl(codeGenerator.generate(), originalUrl, false)), baseUrl);
            } catch (DuplicateKeyException e) {
                // collision: try another code
            }
        }
        throw new ShortUrlException(HttpStatus.SERVICE_UNAVAILABLE, "CODE_GENERATION_FAILED",
                "Could not generate a unique short code. Please try again.");
    }

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

    public ShortUrlView getStats(String code) {
        return Optional.ofNullable(codeGenerator.isValidCodeFormat(code) ? code : null)
                .flatMap(repository::findByCode)
                .map(doc -> ShortUrlView.of(doc, baseUrl))
                .orElseThrow(() -> new ShortUrlException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Short URL not found."));
    }

    /** Current stats for the given codes, in the same order as the codes. */
    public List<ShortUrlView> findAll(List<String> codes) {
        Map<String, ShortUrl> byCode = repository.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(ShortUrl::getCode, Function.identity()));
        return codes.stream()
                .filter(byCode::containsKey)
                .map(code -> ShortUrlView.of(byCode.get(code), baseUrl))
                .toList();
    }
}
