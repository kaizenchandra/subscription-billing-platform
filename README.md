# Accrual · Subscription billing foundation

Java 21 / Spring Boot 4.1.2 / PostgreSQL 17 / Next.js 16 App Router / React / TypeScript / Tailwind 4.

**Status:** implemented and testable financial core with customer and finance portal. The requested full enterprise scope is incomplete. See [architecture](docs/architecture.md), [cloud review, runbooks and gaps](docs/review.md), and [verification](docs/verification.md). No production-readiness claim.

## Run locally
```sh
cd subscription-billing-platform
docker compose up --build
```
Host ports are configurable. To run alongside the other projects in this shared workspace:
```sh
BILLING_POSTGRES_PORT=15543 BILLING_BACKEND_PORT=18089 BILLING_OBJECT_STORAGE_PORT=14566 docker compose up --build
```
With these overrides, use backend port 18089 instead of 8080 in the curl examples below. Frontend remains on 3000.

Open http://localhost:3000 and sign in with `demo` / `local-only`. Local ports bind to loopback. The database volume persists financial history. Do not enable demo authentication outside a local simulator environment.

To generate an immediate invoice (the portal trial checkout intentionally waits seven days):
```sh
curl -u demo:local-only -H 'Content-Type: application/json' -H 'Idempotency-Key: checkout-demo-1' -d '{"plan":"starter-v1","quantity":1,"trialDays":0,"zone":"Asia/Kolkata"}' http://localhost:8080/api/v1/subscriptions
```
Refresh Invoices after a few seconds, choose a simulator scenario and request collection. ACCEPTED_RESPONSE_LOST stays UNKNOWN until Finance → Run reconciliation verifies it. OUTAGE remains unresolved. Inspect balanced entries in Finance. Pausing/resuming/cancelling requires confirmation. The portal never persists authentication in browser storage.

Active, paid subscriptions now support exact plan/seat-change previews in **Subscriptions**. Immediate upgrades create a separate adjustment invoice; renewal changes preserve the current period. Inspect invoice lines with **View lines**. See [plan-change policy and APIs](docs/plan-changes.md).

## Invoice documents

Customer and finance invoice tables now support secured PDF downloads. Original charge documents are frozen after publication and remain unchanged by payment allocations. Compose includes a private S3-compatible LocalStack bucket; standalone Java runs default to local disk. See [document/storage policy and runbooks](docs/invoice-documents.md). The current template is explicitly a sandbox document, with legal billing profiles and jurisdiction-specific taxation still outstanding.

## Razorpay sandbox

Optional invoice-backed Razorpay Standard Checkout and signed webhooks are implemented. Configure `.env.example` with your server-side **test** credentials, then use **Payment methods** for hosted payment entry and verified references. **Finance** includes uncertain-order lookup and webhook status. See [configuration, capability limits and tests](docs/payments/razorpay-sandbox.md). Recurring token mandates and Razorpay refunds are not implemented; simulator operations cannot stand in for them.

## Payment recovery

Choose HARD_DECLINE or SOFT_DECLINE_THEN_SUCCESS in Invoices before collection. **Recovery** displays failure reasons, bounded retry timing, grace, immutable attempt history and notices. For a hard decline, explicitly replace the simulated payment method, then confirm Retry payment. UNKNOWN outcomes block both actions; Finance reconciliation verifies known results. **Finance** includes the dunning queue. Default retries total at most four attempts over seven days, with six-hour exponential backoff plus jitter and a ten-day grace period for previously paid subscribers. Notices are portal-only; no email is sent. See [retry/dunning policy and APIs](docs/dunning.md).

A local nonfinance account `customer / local-only` is also available for authorization testing, in its own tenant.

## Build and verify
```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; use your Java 21 installation elsewhere
mvn -f backend/pom.xml verify
cd frontend
npm ci
npm run build
npm run typecheck
npx playwright install chromium
npx playwright test
```
PostgreSQL tests use real Testcontainers/Docker, with no H2 fallback. Playwright requires the full app on ports 3000/8080. Run `python3 benchmarks/checkout.py --requests 100 --workers 8` only against local simulator data. This script measures checkout, not renewal capacity.

## Source map
- `backend`: application, financial transactions, calendar, workers, security, Flyway schema and deterministic/concurrency tests.
- `frontend`: API forwarding, authenticated portal, pricing, subscriptions, invoices, payments, usage, finance and confirmation flow.
- `compose.yml`: runnable backend/frontend/PostgreSQL/S3-emulator environment.
- `infrastructure/kubernetes.yaml`: backend deployment reference requiring external secret/database and built image.
- `.github/workflows/verify.yml`: workflow template; copy to repository root when hosted as a subdirectory.
- `benchmarks`: simulator-only measurement script.

Production requires BILLING_DEMO=false, PostgreSQL secrets and SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI. JWTs must carry tenant_id; finance tokens need finance scope. Reference manifests are not a completed cloud deployment.
