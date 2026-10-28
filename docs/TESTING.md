# Testing, risks, limitations and trade-offs

## Testing approach

| Level | Tool | What it covers | Location |
|---|---|---|---|
| Unit | JUnit 5 + AssertJ | `UrlValidatorTest` (11): http/https accepted, trimming, empty input, non-URLs, dangerous or missing schemes, length limit, self-reference. `ShortCodeGeneratorTest` (18): code length and charset, 10,000 codes unique, valid/invalid/too-long aliases, reserved aliases in any case. `CreateRateLimiterTest` (2): limit per client, window reset (fixed `Clock`) | `src/test/java/.../service`, `.../web` |
| Web + database | `@SpringBootTest` + MockMvc + Spring Security Test + embedded MongoDB | `ShortUrlWebTest` (12): page contains the `_csrf` field; POST without a token or with an invalid token → 403 and nothing saved; creation with a valid token; recent links shown for that session; custom alias and duplicate → 409 (real unique index); validation messages (empty, `javascript:`, self-reference, reserved alias); cookieless redirect that creates no session, plus click counting; 404 page; JSON stats and JSON 404; CSP and `nosniff` headers; duplicate codes rejected by the database | `src/test/java/.../web/ShortUrlWebTest.java` |
| Orchestrator engine | JUnit 6 with small fake agents | `OrchestratorTest` (19): diamond graph with parallel branches and synchronization, retries with backoff, non-retryable error → fallback, terminal failure → rollback, exit-gate failure, approval before a stage (wrong role refused), rejection → rollback, policy approval with a clarification choice, block policy, `PolicyViolation` (no retry, no fallback), safe-stop + resume, re-plan on a changed requirement (with memoization), re-plan by human note (approval invalidated), downstream waits while upstream awaits approval, exit approval reused for identical output, agent plan changes (allowed and disallowed), stage timeout, tampered audit trail, graph cycle detection | `orchestrator/src/test/.../OrchestratorTest.java` |
| Orchestrator guardrails | JUnit 6 | `WriteGuardAndRequirementsTest` (12): unsafe and protected paths rejected, normal paths allowed, explicit criteria mean no ambiguity, vague wording flagged with options | `orchestrator/src/test/.../WriteGuardAndRequirementsTest.java` |
| Orchestrator end-to-end | `./orchestrate` against this codebase | All three scenarios and the failure drills in [ORCHESTRATION.md §8](ORCHESTRATION.md#8-cli-walkthrough); each run compiles the changed workspace and runs this project's full test suite | n/a |
| Manual | Browser + curl via `./mvnw spring-boot:run -Pembedded-mongo` | Create → copy → follow link → refresh click count; `curl -X POST /shorten` without a token → 403; redirect returns 302 with no `Set-Cookie` | n/a |

Run the app's tests (43) and the orchestrator's tests (31) with:

```bash
./mvnw test
./mvnw -f orchestrator/pom.xml test
```

The web tests run against a real MongoDB server (flapdoodle downloads MongoDB 7.0 on the first run and caches it), started once per test context. Test settings live in `src/test/resources/application-test.yml` (`base-url: http://short.test`, a high rate limit).

Why an embedded MongoDB rather than mocks: the most important correctness property (codes are unique, and duplicates surface as `DuplicateKeyException` and get retried or turned into `409`) lives in the database's unique index. Mocking it would test the mock. It also checks the real `findAndModify` click counting.

Why MockMvc with `csrf()`: Spring Security Test's `csrf()` post-processor adds a valid token, and `csrf().useInvalidToken()` a wrong one, so both the accepted and rejected paths are tested through the real security filter chain.

## Risks and how they're handled

| Risk | Impact | Mitigation |
|---|---|---|
| Short code collision | Wrong redirect | Unique index + bounded retry (5). A collision is ~1 in 3.5 trillion per insert |
| Open-redirect / phishing use | Reputation | Rate limiting. Future: domain blocklist ([Scenario 3](SCENARIOS.md#scenario-3-ambiguous-make-short-links-safer)) or a Safe Browsing check |
| MongoDB unavailable at startup | App can't start | Startup fails after the driver's 30 s server-selection timeout (index creation needs the database), so a broken deployment is visible immediately. `-Pembedded-mongo` for local runs |
| MongoDB unavailable later | Requests fail | Requests that need the database return the generic error page; `/health` does not check the database yet (see Limitations) |
| Abuse / flooding | Storage growth | Per-IP rate limit (429), form size limits, 2048-char URL limit |
| Hot redirect path slowdown | Latency | Single indexed atomic `findAndModify` per redirect |
| Session cookie sent over plain HTTP in production | Session/CSRF token exposure | `SECURE_COOKIES=true` behind HTTPS; cookie is always `HttpOnly` and `SameSite=Strict` |

## Limitations (v1)

- No user accounts: links can't be edited, deleted or listed per user. "Recent links" lives only in the user's session (lost when it expires).
- No link expiry in `src/` yet ([Scenario 2](SCENARIOS.md#scenario-2-brownfield-optional-link-expiry)).
- No machine-to-machine API ([future scenario](SCENARIOS.md#future-scenario-machine-to-machine-api)).
- Rate limiter and sessions are in memory, so they're per instance and reset on restart.
- Analytics are aggregate only (count + last access). No referrer, geography or time series.
- Destination URLs aren't checked for malware or phishing.
- `/health` doesn't check the database connection (Spring Boot Actuator's health endpoint would add this).
- `java.net.URI` is strict: URLs with unencoded spaces or some special characters are rejected and must be encoded first.
- Link expiry and the domain blocklist exist as reviewed change plans run by the orchestrator; by default they are released into a sandbox copy, not into `src/` (use `--release-to project` to promote them).

## Trade-offs

| Choice | Gained | Given up |
|---|---|---|
| 302 instead of 301 | Every click counted; destination can change later | Slightly slower repeat visits (no browser caching) |
| Count clicks synchronously in the redirect | Simple, exact, atomic | One DB write per click. At very high volume, batch or queue it |
| New code for every submission | Separate analytics per link, no information leak | Duplicate rows for popular URLs |
| Spring Security's session-based CSRF | Standard, well-reviewed, automatic in Thymeleaf | Needs an HTTP session for UI users (sticky sessions or Spring Session when scaled out) |
| Server-rendered form with Post/Redirect/Get | No JavaScript needed; no double submits | Full page reload on every create |
| Recent links in the session | No global list, no extra storage | Lost when the session ends; not shared across devices |
| Embedded MongoDB for tests | Real database behaviour, no Docker | First run downloads ~100 MB |

## Orchestrator limitations

- **Change plans are written and reviewed by engineers.** Agents select, apply, validate and document them; they do not invent code. A requirement with no matching plan is handed off (CHG-008).
- **Single-process scheduler** with a file lock; no distributed worker pool.
- **File-based store and audit log.** The hash chain detects edits, deletions and reordering, but nothing anchors the latest hash externally.
- **Pattern-based security rules** plus an OSV.dev dependency audit; not a full SAST tool. The audit needs network (otherwise the static-only fallback runs and SEC-003 warns).
- **One clarification choice per approval**, keyword-based traceability, and approver identity from a CLI flag.

More detail: [ORCHESTRATION.md §10](ORCHESTRATION.md#10-limitations-and-trade-offs).
