# Razorpay sandbox: Checkout, verification, and signed webhooks

This adapter supports customer-present payments against finalized INR invoices via Razorpay Standard Checkout. It is disabled by default, refuses live keys, and does not claim recurring mandate support. The simulator remains the default runnable provider. Automatic simulator collection is suppressed whenever Razorpay is enabled, so an invoice is not silently paid before a customer opens Checkout.

## Verified capability boundary

Implemented capabilities: server-side order creation and lookup, hosted customer-present payment entry, callback signature verification followed by payment status lookup, captured-payment allocation, signed webhook inbox and redacted method references. Authorization is not capture; neither is settlement. Configure automatic capture in the Razorpay test dashboard or capture there according to your account's policy. This adapter does not make authorization/capture, refund, mandate or token-charge requests.

Razorpay's [Standard Checkout integration](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps/) documents invoice-order checkout and server-side signature verification. Its [recurring payments API](https://razorpay.com/docs/api/payments/recurring-payments/) is an on-demand account feature requiring a separate authorization/token flow. These APIs are not interchangeable. A stored `pay_...` reference is not a reusable token or recurring mandate. Free-trial method setup without a charge, recurring collection through Razorpay, external refunds, token revocation, and settlement reconciliation remain unsupported. Simulator retries/refunds cannot be applied to Razorpay payments.

## Configure an actual test account

No account credentials are included in source or required by automated tests. In the project directory, copy `.env.example` to `.env` and populate it from your Razorpay **test-mode** dashboard:

```text
BILLING_RAZORPAY_ENABLED=true
BILLING_RAZORPAY_KEY_ID=<rzp_test_...>
BILLING_RAZORPAY_KEY_SECRET=<test API secret>
BILLING_RAZORPAY_ACCOUNT_ID=<acc_... for that merchant account>
BILLING_RAZORPAY_WEBHOOK_SECRET=<separate random secret, at least 16 characters>
BILLING_RAZORPAY_PREVIOUS_WEBHOOK_SECRET=<optional old webhook secret during rotation>
```

The Java configuration fails startup on live/non-test keys or missing account/secret configuration. Only the public key ID is sent to the browser; API and webhook secrets remain server-side. Production secret-manager integration is still deployment work. Do not paste populated credentials into tickets, screenshots, repository files or logs.

```sh
docker compose up --build
```

1. Create a subscription with no trial using the README curl example, then allow its invoice to be generated.
2. Open **Payment methods**. The exact finalized balance appears before confirmation. Open Razorpay's hosted Checkout; use official test instruments from your Razorpay dashboard/documentation.
3. A signed callback is verified using the saved server-side order ID, followed by an authenticated provider status lookup. Only an exact captured amount/currency/order match pays the invoice and posts its journal. If status is authorized, the portal waits for capture rather than reopening Checkout.
4. After capture, inspect the verified method reference. Only method type and optional last four digits are stored locally, alongside the provider payment ID in financial evidence. Archiving hides a local reference; it does not revoke a provider token, cancel a mandate or refund anything.

Use a controlled public HTTPS staging endpoint for real Razorpay webhook delivery, targeting the backend directly:

```text
POST /api/v1/webhooks/payments/razorpay
```

Subscribe to `payment.captured`, `payment.authorized`, `payment.failed` and optionally `order.paid`. Set the configured webhook secret in the same test account. Local Compose binds to loopback and is not itself a public callback destination. Follow Razorpay's [webhook validation/testing guide](https://razorpay.com/docs/webhooks/validate-test/) for currently supported staging/tunnel options. This phase does not publish a staging endpoint or configure your dashboard.

## Financial boundaries and uncertain outcomes

`payments.provider` prevents simulator workers, reconciliation, method replacement, retries or refunds from inventing external effects. One logical payment and one Checkout/order-operation identity reserve each invoice. A database claim permits only one application-level order POST. Receipt is the checkout UUID (36 characters), preserving a stable dashboard lookup reference. Different idempotency keys still recover the same invoice checkout rather than create another order.

The provider client uses a fixed HTTPS API origin, no redirects, four concurrent calls, two-second connection timeout, an explicit five-second full-response deadline, and a 64 KiB response cap. It never retries POST requests in application code. Provider exceptions contain no response bodies or credentials. JSON amounts must be nonnegative integer minor units; fractional or overflowing amounts are rejected.

All remote calls run **outside database transactions**. Before the call, the operation and claim commit. A crash/timeout/error after claiming leaves CREATING/UNKNOWN and cannot automatically issue another POST. A worker marks abandoned CREATING records uncertain after one minute. This conservative policy also treats unconfirmed HTTP errors as unknown rather than guessing whether the provider created an order.

Finance can recover a lost creation response: find the order by receipt in the test dashboard, then use **Finance → Razorpay sandbox operations → Verify existing order**. The adapter fetches the existing order and verifies its receipt, amount and currency before binding it. This is an authenticated [order lookup](https://razorpay.com/docs/api/orders/fetch-with-id/), never a second POST. Binding does not mark an invoice paid; a subsequent verified capture is required. Late/missing webhook mapping can then be reprocessed. There is no operator shortcut to set payment success manually.

## Webhook security and durability

- The exact raw bytes are HMAC-SHA256 verified with a constant-time digest comparison before JSON parsing. Current and optional previous webhook secrets support delayed delivery during rotation.
- Account ID must match configured credentials. Tenant/invoice ownership is derived exclusively from the known order-to-checkout mapping; body notes/customer IDs cannot select an invoice.
- The anonymous route is limited to this one webhook path. Customer/admin APIs retain authentication, subject/tenant checks and finance RBAC.
- Request bodies are capped at 64 KiB. The inbox stores a SHA-256 body digest and necessary payment fields only, not raw payloads, email, phone, VPA, PAN, CVV or provider response bodies.
- Durable uniqueness on `(account_id, event_id)` and `(account_id, body_hash)` handles provider redelivery and changed event headers. Reuse of an event ID with a different payload conflicts. Financial idempotency is also enforced by the unique order/payment mapping and locked logical payment.
- Valid webhook acknowledgement means the normalized event committed to the inbox. It does not imply capture allocation. Database failure rolls back ingestion so delivery can be retried.
- Captured evidence validates order, amount and currency against the immutable invoice reservation. Allocation, the balanced journal, outbox and method reference commit together. Capture/order-paid events may race without duplicate posting. Late authorization/failure cannot downgrade a captured payment.
- Failed Checkout attempts are observational and do not finalize the platform's order operation as a declined capture: the customer may retry within the same unpaid provider order. They do not schedule automatic Razorpay charges.
- A webhook arriving before order mapping remains durable, with 30-second rescheduling for up to 20 mapping attempts, then REVIEW. Amount/currency/balance or unexpected second-capture discrepancies go to REVIEW with durable evidence. Verified order recovery requeues REVIEW events for that order; validation still runs on replay.

Razorpay does not provide a universally applicable signed request timestamp header in this integration. No invented five-minute cutoff rejects legitimate delayed events. Indefinite durable event/financial identities provide replay safety; source digest and account binding reject mutation. Timestamp-based access throttling, ingress WAF/rate limits, retention administration and alerting remain deployment work.

## API contracts

All customer routes require authenticated invoice/checkout ownership (tenant plus subject); finance routes require finance scope/role and tenant ownership. Commands return 200; validation 400; ownership 404; bad signatures 401; financial conflicts/uncertain setup 409; disabled setup 503; oversized webhook 413. Reads are bounded to 100 rows; cursor pagination is outstanding.

| Route | Contract |
|---|---|
| GET /api/v1/payments/providers | Enabled test-mode flag and explicit adapter capabilities |
| POST /api/v1/payments/setup | `{invoice}` and Idempotency-Key; exact finalized invoice balance, minimum 100 INR minor units |
| GET /api/v1/payments/checkouts/{id} | Existing checkout, public key, amount, order, current state |
| POST /api/v1/payments/checkouts/{id}/verify | `{paymentId,signature}`; server order identity plus provider status lookup; repeat-safe |
| GET /api/v1/customers/me/checkouts | Owned checkout history |
| GET /api/v1/customers/me/payment-methods | Redacted verified references; no chargeable token returned |
| POST /api/v1/customers/me/payment-methods/{id}/archive | Idempotency-Key; local archival only |
| POST /api/v1/webhooks/payments/razorpay | Anonymous HMAC-authenticated raw body, X-Razorpay-Signature and X-Razorpay-Event-Id |
| GET /api/v1/admin/razorpay/checkouts | Tenant-scoped finance investigation |
| GET /api/v1/admin/razorpay/events | Tenant-mapped normalized webhook status; unmapped events require database/runbook investigation |
| POST /api/v1/admin/razorpay/checkouts/{id}/reconcile-order | `{orderId}`; repeat-safe GET-based verification of an existing provider order |

## Automated tests versus actual sandbox verification

Automated HTTP contract tests run the real adapter against a local HTTP server. PostgreSQL tests use the real Spring security, transaction, inbox, worker and ledger stack with an explicitly injected local provider fixture. The browser suite intercepts only Razorpay's hosted script and uses that local provider fixture; it does not pretend to exercise Razorpay's hosted UI or your merchant account. Test classes/fixture keys are not packaged in the production jar.

To repeat the opt-in browser contract suite after starting a local database on port 55439:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -f backend/pom.xml verify dependency:build-classpath -Dmdep.outputFile=/tmp/billing-razorpay-classpath
BILLING_DEMO=true BILLING_AUTO_COLLECT=false DATABASE_URL=jdbc:postgresql://localhost:55439/billing \
 java -cp "backend/target/test-classes:backend/target/classes:$(cat /tmp/billing-razorpay-classpath)" \
 com.acme.billing.RazorpayPortalFixture --server.port=18089
# In another terminal:
cd frontend
npm run build
BACKEND_URL=http://127.0.0.1:18089 npm start
# In another terminal, from frontend/:
RAZORPAY_CONTRACT_TEST=true npx playwright test
```

Actual Razorpay test-account API calls, hosted test payments and dashboard webhook delivery remain unverified until test credentials and a staging callback are configured. No live keys, real payments, dashboard changes or external deployment were performed in this implementation.

A convenience runner, `scripts/verify-razorpay-portal.sh`, performs these local fixture steps and removes its own temporary database on exit. It requires Java 21, Maven, Node, Docker and free ports 55439/18089/3000. It does not use actual merchant credentials. Its component commands were executed during verification; the aggregate script was syntax-checked separately.
