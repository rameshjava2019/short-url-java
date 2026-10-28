# Scenarios

Three scenarios (greenfield, brownfield and ambiguous), each run through the SDLC orchestrator against this Spring Boot codebase. Each section shows the decomposition, the impact on the Java classes, the orchestration path and the validation.

All three are executable:

```bash
./orchestrate run <scenario-id>
```

| Scenario | Requirement | Change plan | Type |
|---|---|---|---|
| Usage statistics endpoint (`greenfield-stats`) | [requirement.md](../orchestrator/scenarios/greenfield-stats/requirement.md) | `GreenfieldStatsPlan.java` | Greenfield: new service and endpoint |
| Optional link expiry (`brownfield-expiry`) | [requirement.md](../orchestrator/scenarios/brownfield-expiry/requirement.md) | `BrownfieldExpiryPlan.java` | Brownfield: change existing classes across every layer |
| "Make short links safer" (`ambiguous-safer-links`) | [requirement.md](../orchestrator/scenarios/ambiguous-safer-links/requirement.md) | `SaferLinksPlans.java` | Ambiguous: several valid interpretations |

Change plans are in `orchestrator/src/main/java/com/example/orchestrator/scenarios/`. Requirement files can be edited to trigger a re-plan. How the orchestrator works is described in [ORCHESTRATION.md](ORCHESTRATION.md). The URL shortener itself was the original greenfield build; its decomposition is in [REQUIREMENTS.md](REQUIREMENTS.md).

Every run releases into a sandbox copy of the project (`.orchestrator/runs/<runId>/release-target`) unless started with `--release-to project`, so the scenarios can be run repeatedly without changing `src/`.

---

## Scenario 1: Greenfield, usage statistics endpoint

**Requirement:** Operations wants total links and total clicks without querying MongoDB by hand. Four explicit acceptance criteria: `GET /api/stats` returns both totals as JSON; read-only with no CSRF; aggregated in the database; documented in the README.

**Decomposition** (tasks trace to criteria by keyword):

| Task | Covers | After |
|---|---|---|
| T1 `StatsService`: one `$group` aggregation via `MongoTemplate.aggregate`, returning a `UsageStats(totalLinks, totalClicks)` record | AC-1, AC-3 | n/a |
| T2 `GET /api/stats` in `ApiController` (read-only, no CSRF) | AC-1, AC-2 | T1 |
| T3 `StatsWebTest`: zeros with no links, sums after real redirects, no session created | AC-1 | T2 |
| T4 README endpoint row | AC-4 | T2 |

**Impact analysis:** creates `service/StatsService.java` and `web/StatsWebTest.java`; modifies `web/ApiController.java` (constructor gets the new service) and `README.md`. `SecurityConfig` needs no change because Spring Security only checks CSRF on state-changing requests. No security-sensitive files and no document change, so the plan is not extended.

**Orchestration path:**

```
requirements → design → implementation (compiles) → (test ∥ security ∥ docs) → readiness → ⏸ release (release-manager) → release
```

**Validation (verified run):**

| Gate / check | Result |
|---|---|
| `spec-actionable`, `criteria-traceable` | 4 criteria, all traced |
| `compiles` | `mvn test-compile` in the workspace |
| Tests | 46/46 (43 existing + 3 new); test methods do not drop (CHG-005) |
| Security | 0 findings; 71 runtime dependencies checked against OSV.dev |
| Docs | README mentions `/api/stats` (plan, and the new `@GetMapping` found in the code) |
| Release | Full suite re-run in the sandbox release target |

---

## Scenario 2: Brownfield, optional link expiry

**Requirement:** campaign links should stop working when the campaign ends. Five acceptance criteria: an optional expiry (1-365 days) in the form; expired links return `410 Gone`; links without expiry never expire and existing links keep working; stats include `expiresAt`; README updated.

**Decomposition:**

| Task | After |
|---|---|
| T1 `ShortUrl`: optional `Instant expiresAt` (null = never), new 4-argument constructor, the old one kept | n/a |
| T2 `ShortUrlService.create(url, alias, expiresInDays)`: validate 1-365 (`400 EXPIRY_INVALID`); old 2-argument `create` kept | T1 |
| T3 `resolveAndTrackClick` returns a sealed `Resolution` (`Found`, `Expired`, `NotFound`); `RedirectController` renders `410` + `expired.html` | T2 |
| T4 `CreateLinkForm.expiresInDays`, expiry `<select>` in `index.html`, CSS | T2 |
| T5 `ExpiryWebTest` (7 tests): expiry date set, expired → 410 and no click counted, no expiry → never expires, a document inserted without the field still redirects, 0 and 366 rejected, stats include `expiresAt`, unknown → 404 | T3 |
| T6 README | T3 |

**Impact analysis:** 12 files (model, view record, new `Resolution`, service, redirect controller, new template, form, home controller, index template, CSS, new test, README). The hot path keeps one atomic `findAndModify`, now limited to non-expired links (`expiresAt == null OR expiresAt > now`); only a miss runs one `exists()` query to tell "expired" from "unknown". A TTL index was considered and rejected (it would free the alias for someone else and lose stats).

**Orchestration path:**

```
requirements → design ⏸ CHG-001 (tech-lead: 12 files > 8)
             → [+ dataCompatibility added: a MongoDB document changes]
             → implementation → (test ∥ security ∥ docs ∥ dataCompatibility) → readiness
             → ⏸ release (release-manager) → release
```

This shows both a policy-driven checkpoint and an agent-driven plan change. With `--inject test=1`, the first test attempt fails and is retried, which shows up in the audit trail and the retry metrics.

**Validation (verified run):**

| Check | Result |
|---|---|
| Tests | 50/50 (43 existing + 7 expiry tests) |
| Data compatibility | New field `expiresAt` is nullable, so existing documents read it as `null` (never expires); no fields removed; no new unique index; no migration needed |
| Docs | README mentions `expiresInDays` and `410` |
| Rollback plan | Revert; the previous code ignores the extra field |

**Re-plan demo:** edit `requirement.md` and run `./orchestrate resume <runId>`. The orchestrator registers a new version of the requirement, re-runs `requirements`, and re-runs only the downstream stages whose inputs actually changed.

---

## Scenario 3: Ambiguous, "make short links safer"

**Requirement (verbatim):** "We had a complaint that one of our short links pointed somewhere nasty. Short links should be safer for the people who click them."

**Requirement understanding:** there is no acceptance-criteria section, so the requirements agent derives one vague criterion from the "should" sentence and flags "safer" as ambiguous, with three interpretations from its knowledge base:

| Option | Meaning |
|---|---|
| `domain-blocklist` | Refuse to shorten URLs on a configurable list of blocked domains |
| `interstitial-warning` | Show a preview / warning page before redirecting |
| `link-expiry` | Let links expire so old links stop working |

**Human checkpoint:** REQ-001 pauses the run for a **product-owner**, who must approve with `--choice`. The choice becomes the `requirements.clarification` artifact the design consumes; the design entry gate makes sure design never runs on an unresolved spec.

**Path A: `--choice domain-blocklist`** (a reviewed plan exists)

- Refined, testable criteria: blocked domains and their subdomains rejected with `400 URL_DOMAIN_BLOCKED`; look-alike domains (`notevil.com`) not blocked; configured with `BLOCKED_DOMAINS` and documented.
- Tasks: `AppProperties.blockedDomains` (trimmed, lower-cased; `@ConstructorBinding` on the canonical constructor, and the old 2-argument constructor kept so existing code and tests compile) + `app.blocked-domains: ${BLOCKED_DOMAINS:}` → `UrlValidator` check → unit + MockMvc tests → README.
- Impact touches `AppProperties.java` and `application.yml`, which are **security-sensitive**, so the design agent adds a `securityReview` stage before implementation. It needs a **security-reviewer** approval, and SEC-002 independently blocks any change to those files without it.

```
requirements ⏸ clarification (product-owner) → design → [+ securityReview ⏸ (security-reviewer)]
             → implementation → (test ∥ security ∥ docs) → readiness → ⏸ release (release-manager) → release
```

- Validation (verified run): 52/52 tests (43 existing + 7 unit cases + 2 MockMvc); docs check finds `BLOCKED_DOMAINS` (the new `${...}` in `application.yml`) and `URL_DOMAIN_BLOCKED` in the README.

**Path B: `--choice interstitial-warning`** (no reviewed plan)

- The design agent finds no matching plan and produces a hand-off task list instead.
- CHG-008 blocks the run: nothing is implemented, and `status` shows the hand-off tasks for a human engineer.

This is the controlled-autonomy boundary: agents automate only reviewed plans, and anything else goes back to a human.

---

## Future scenario: machine-to-machine API

"Other teams want to create short links from their services." The UI form is CSRF-protected and other services have no browser session, so the recommended design is a separate `POST /api/v1/links` with a JSON body, authenticated by hashed API keys:

- a second `SecurityFilterChain` with `securityMatcher("/api/v1/**")`, stateless sessions, CSRF disabled for that chain only, and an API-key authentication filter
- per-key rate limits
- reusing `ShortUrlService.create`

Disabling CSRF for the UI form would be rejected (the security agent's invariant INV-001 would also block it). Before a change plan is written, a product and security owner must decide who may call it, which is what the REQ-001 checkpoint is for.
