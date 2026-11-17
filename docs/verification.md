# Verification evidence · 2026-11-17

Environment: macOS arm64, Java 21.0.13, Maven 3.10, Node 26.13.1, OrbStack Docker, PostgreSQL 17.12 (postgres:17-alpine), Boot 4.1.2, Next.js 16.4.6 resolved in package-lock.json.

## Executed

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f backend/pom.xml verify`: PASS, 8 tests, zero failures/errors/skips. Six calendar/proration tests; two real PostgreSQL tests. Testcontainers migrations executed successfully.
- PostgreSQL concurrency test: three same-key checkout callers share a subscription; three duplicate renewal callers produce one invoice; ambiguous simulator capture is reconciled once; cross-tenant refund access fails; excess refunds fail; finalized total mutations and journal deletion fail; conflicting idempotency payload fails.
- Spring application test: boots the actual security/filter/controller/transaction/scheduler stack on a random port, submits authenticated HTTP checkout and observes automatically collected PAID invoice. This caught a proxy-field-access bug during live smoke testing; JdbcTemplate is now injected directly where queried.
- `npx next build --webpack`: PASS, production App Router build and TypeScript compilation. Turbopack failed in this environment while attempting a worker port bind; package build uses supported Webpack instead.
- `npm run typecheck`: PASS.
- `npx playwright test`: PASS, 2 tests in 3.0 seconds. Authenticated catalog loading; real checkout, UNKNOWN simulator payment, reconciliation/replay, PAID invoice rendering and mobile viewport visibility. A non-exact invoice locator failed initially and was corrected to target the exact invoice-number cell. These tests do not establish WCAG conformance.
- `npm install`: audit reported zero vulnerabilities at installation; this is not a security assessment.

## Measured sample

`python3 benchmarks/checkout.py --url http://127.0.0.1:18089 --requests 100 --workers 8` against a packaged local backend and isolated PostgreSQL:

| Metric | Observed |
|---|---:|
| Requests / successes | 100 / 100 |
| Elapsed | 1.295 seconds |
| Achieved checkout TPS | 77.218 |
| p50 | 94.132 ms |
| p95 | 158.078 ms |
| p99 | 196.964 ms |

Small closed-loop sample, eight threads, shared developer machine, no warmup or steady-state assurance. Billing workers were also running; automatic collection was disabled for manual scenario verification. An earlier run against an unavailable endpoint had zero successes and is not a throughput result. No renewal/ledger/usage benchmark or 100/1000/5000/10000 renewal TPS target was established. No p99 SLO or high-availability claim follows from this sample.

## Not verified

Docker image builds and full Compose startup, Kubernetes/AWS deployment, production OIDC issuer integration, accessibility conformance, Kafka delivery, provider webhooks, real financial operations, backups/restores, all specified failure cases, and sustained load/chaos tests. Source templates are not deployment evidence. See review.md for feature gaps.

## Continuation: plan changes and scheduler fencing · 2026-11-17

- Java 21 `mvn -f backend/pom.xml verify`: PASS, 13 tests (6 calendar and 7 PostgreSQL/application tests), zero failures/errors/skips. V1–V4 migrations execute against PostgreSQL 17 Testcontainers.
- New integration evidence: exact half-period old credit 24950/new charge 74950/net 50000 INR minor units; same-key replay; distinct-key stale quote rejection; expiry at 300 seconds; cross-tenant rejection; immutable adjustment lines; multiple upgrades after paying prior adjustment; immediate downgrade and cross-interval rejection; scheduled seat changes apply once at renewal; cancellation invalidates a quote; distinct duplicate job IDs cannot bill another cycle; upgrade-versus-renewal race yields one serialized financial outcome.
- `npm run build --prefix frontend`: PASS with Webpack.
- `npm run typecheck --prefix frontend`: PASS.
- `npx playwright test`: PASS, 3 tests in 7.1 seconds against the built frontend and a packaged backend using a separate real PostgreSQL instance. Added customer UI preview/confirmation and signed adjustment-line total checks; previous sign-in/reconciliation regressions pass.
- Temporary servers and verification database container were stopped after execution. No new benchmarks or cloud deployment were performed in this continuation.

These results establish the tested fixed-price zero-tax plan-change scope. They do not establish the still-outstanding master-prompt features listed in review.md and plan-changes.md.

## Continuation: bounded retries and dunning · 2026-11-17

- Java 21 `mvn -f backend/pom.xml verify`: **PASS: 26 tests**, zero failures/errors/skips (6 calendar, 7 existing PostgreSQL/application, 11 dunning/authorization/migration, 2 retry policy).
- V1–V8 migrations execute on real PostgreSQL Testcontainers. A dedicated V7-to-V8 upgrade fixture proves existing simulator method-recovery keys remain idempotent even without outbox rows.
- Deterministic clocks verify total-attempt caps, retry-window bounds, exponential jitter, provider Retry-After, no early retry, no retry of pending/unknown outcomes, explicit hard-decline recovery, method-update replay independent of outbox retention, and immutable completed attempt history.
- Concurrent same-key retries create one new attempt and one capture journal. Reminder redelivery creates one notice of each kind; payment completion racing reminder processing ends RECOVERED without duplicate financial effects. Recovering one invoice does not clear another unresolved failure. Resume cannot bypass a failure received while paused.
- Real Spring HTTP tests exercise finance queue/attempt inspection, customer-role 403, cross-tenant retry 404, initial-unpaid access denial, previously paid grace access, and time-based access restriction. A real scheduled worker automatically recovers SOFT_DECLINE_THEN_SUCCESS within a short test-only policy.
- `npm run build --prefix frontend`: PASS; `npm run typecheck --prefix frontend`: PASS.
- `npx playwright test`: 5 passed in 16.2 seconds against the built frontend and packaged application. Covers previous catalog/reconciliation/proration flows plus explicit hard-decline method recovery, manual retry confirmation, immutable attempt-history display, finance queue and disabled actions for UNKNOWN. Subsequent resume and finance-response changes were covered by backend tests; no frontend source changed after this browser run.
- No real payment, external email/SMS, new load benchmark, or cloud deployment occurred. Portal notices and simulator method replacement are explicitly scoped as local implementations. Existing benchmark results do not establish retry throughput or production SLOs.

Temporary recovery verification servers and the isolated database container were stopped after the run.

## Continuation: Razorpay test-mode adapter and signed webhooks · 2026-11-17

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f backend/pom.xml verify`: **38 tests passed**, zero failures/errors/skips. Counts: 6 calendar, 11 dunning, 7 existing PostgreSQL, 7 Razorpay integration/security/concurrency, 2 retry policy, 5 real HTTP adapter/signature tests. PostgreSQL Testcontainers executes V1–V10 migrations.
- HTTP contract tests use the actual adapter against a local server and verify Basic auth/order fields, no POST replay after HTTP failure, full-body timeout/cancellation, bounded response size, fractional-money rejection and raw-byte HMAC verification. A stalled-body test exposed a header-only timeout assumption; the client now explicitly cancels the response future after five seconds. A targeted rerun also verifies previous-webhook-secret rotation.
- Razorpay integration tests use a real Spring application and PostgreSQL with an explicitly injected provider fixture: concurrent setup creates one order, capture/order-paid races post one journal, duplicate/changed-header replay is deduplicated, late authorization/failure cannot undo capture, invalid signatures/account/amount cannot allocate funds, cross-tenant reads/archive fail, webhook delivery can precede order binding, and a lost creation response cannot cause repost. Verified GET-based order recovery can subsequently process capture. Actual provider calls are asserted outside transactions.
- Anonymous HTTP webhook route accepts signed deliveries, rejects missing signatures (401) and oversized bodies (413), while customer routes remain authenticated and cross-tenant checkout reads return 404.
- `npm run build --prefix frontend`: **PASS**, production App Router/Webpack build.
- `npm run typecheck --prefix frontend`: **PASS**.
- `RAZORPAY_CONTRACT_TEST=true npx playwright test`: **8 passed in 15.2 seconds** against a built frontend, real backend and isolated PostgreSQL. Includes prior lifecycle/proration/dunning flows plus provider checkout callback verification, reference archival and duplicate signed webhook/tampered-body checks.
- Browser provider tests replace Razorpay's external hosted script and use `RazorpayPortalFixture` from test classes. These prove platform wiring and financial behavior, not Razorpay's hosted UI or merchant-account acceptance. Fixture keys and provider substitutes are not packaged in the production jar.
- `scripts/verify-razorpay-portal.sh` reproduces the component commands and cleans up its own temporary services; `bash -n` passed. The aggregate script itself was not executed as one command in this session.
- No real Razorpay test-account API request, hosted test transaction, dashboard webhook configuration, live financial operation, new throughput benchmark or cloud deployment was performed. Real account verification requires configured test credentials and a controlled public HTTPS callback. Recurring mandates, provider refunds and settlement reconciliation remain unsupported capabilities.

The targeted final signature/HTTP rerun passed all five tests, including previous-secret rotation. Temporary Razorpay fixture servers and the isolated verification database were stopped after the browser run.

## Continuation: invoice PDF artifacts and secured S3 delivery · 2026-11-17

- Java 21 `mvn -f backend/pom.xml verify`: **47 tests passed**, zero failures/errors/skips. Existing 38 regressions plus 4 document transaction/security tests, 3 renderer tests and 2 S3 protocol/application tests. PostgreSQL executes V1–V12 migrations.
- A targeted `mvn -f backend/pom.xml -Dtest=InvoiceDocumentsTest test` rerun passed all four tests after adding explicit outbox-deletion coverage: issuance survives retention, historical bytes remain identical after payment, other tenants/subjects cannot trigger storage reads, and finalized artifact/issue/ownership fields reject mutation.
- Document tests cover concurrent generation, one published artifact, expired-lease fencing, transient upload failure without financial effects, corrupt-storage rejection and private customer/finance HTTP routes. Storage work is asserted outside PostgreSQL transactions.
- Real LocalStack S3 tests exercise conditional non-overwrite writes, retrieval, private ACL metadata, path/size validation and full Spring HTTP → render → S3 → authenticated download. These tests do not establish AWS IAM enforcement, encryption at rest or cloud backup/restore behavior.
- PDFBox text extraction validates signed credits/charges, finalized totals and JPY/KWD decimal scales. Long descriptions and 80 additional lines paginate without loss. Proration and multi-page PNGs were rendered and visually inspected; an actual PDF downloaded from the Compose deployment was also rendered and inspected. Poppler was unavailable, so PDFBox's renderer supplied visual QA. No legal/tax-compliance claim is made.
- `npm run build --prefix frontend`: **PASS**, production App Router/Webpack build. `npm run typecheck --prefix frontend`: **PASS**.
- Docker Compose backend and frontend images **built successfully**. PostgreSQL, backend, frontend and initialized LocalStack S3 bucket **started successfully**. A subsequent build/restart upgraded existing test invoices to V12. Configurable host ports 15543/18089/3000/14566 avoided other running projects; the final Compose source was also started successfully with these overrides.
- `npx playwright test tests/portal.spec.ts`: **7 passed in 19.7 seconds** against the final Docker-built stack and its S3 storage. New coverage confirms browser PDF download, preserved binary bytes/content type, cached byte equality, no-store, cross-tenant rejection and finance download controls. The two opt-in Razorpay browser fixture cases were not rerun in this phase; backend Razorpay regressions passed in the full suite.
- No AWS deployment, actual AWS bucket/security-policy change, live payment, new benchmark or scale validation occurred. Default Compose storage is a local S3 emulator. Standalone Java also supports local disk. Legal billing profiles, jurisdiction-specific invoice/tax certification, credit-note/receipt PDFs, international typography, retention administration and cloud recovery exercises remain outstanding.

Reproduce the isolated local deployment from the project directory:

```sh
BILLING_POSTGRES_PORT=15543 BILLING_BACKEND_PORT=18089 BILLING_OBJECT_STORAGE_PORT=14566 docker compose up --build
```

The frontend remains on http://localhost:3000 and backend on http://localhost:18089. Only temporary verification-project containers/volumes were removed after the checks; unrelated projects were left running.
