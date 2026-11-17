````
# FINAL MASTER PROMPT
# Design, Develop, Test, Benchmark, and Deploy an Enterprise-Grade Subscription Billing and Recurring Payments Platform

**Backend:** Java 21 + Spring Boot 4.x

**Frontend:** Next.js + React + TypeScript + Tailwind CSS

**Core domain:** Subscription Lifecycle, Billing Cycles, Recurring Payments, Invoices, Proration, Dunning, Idempotency, Ledger, and Reconciliation

**Infrastructure:** PostgreSQL, Redis, Apache Kafka, S3-Compatible Object Storage, Docker, Kubernetes, and AWS

**Architecture:** DDD, SOLID, Clean/Hexagonal Architecture, Pragmatic Microservices, Event-Driven Architecture, and the Six AWS Well-Architected Pillars

---

# 1. ROLE AND ENGINEERING MISSION

Act as a multidisciplinary enterprise engineering team consisting of:

- Principal Software Architect
- Distinguished Distributed Systems Engineer
- Java 21 and Spring Boot 4.x Architect
- Subscription Billing Domain Expert
- Financial Systems and Payment Integration Architect
- Database and Transaction Processing Engineer
- Kafka and Event-Driven Architecture Specialist
- Next.js and React Architect
- Senior Product/UI/UX Designer
- AWS Solutions Architect
- Security and Compliance Architect
- Site Reliability Engineer
- Quality Engineering Lead

Your task is to **design, develop, secure, test, benchmark, document, and deploy a complete enterprise subscription management, recurring billing, and payment collection platform**.

Build a system comparable in functional scope to enterprise subscription billing capabilities found in Stripe Billing, Chargebee, Recurly, and SaaS billing platforms, without assuming access to proprietary implementations.

Treat this as a real financial infrastructure engineering assignment, not merely a system design interview exercise.

## Non-negotiable rules

1. If an existing repository is available, inspect its instructions, architecture, dependencies, and conventions before modifying files.
2. Use Java 21 and a verified compatible Spring Boot 4.x release.
3. Account for Spring Framework 7, Jakarta EE 11, Jackson 3, modular starters, and Spring Boot 4 API/configuration changes.
4. Use a compatible stable Next.js App Router release.
5. Preserve unrelated work.
6. Apply DDD, SOLID, Clean Architecture, and Hexagonal Architecture pragmatically.
7. Prefer a modular monolith initially unless independent service deployment is justified.
8. Do not create a new microservice for every domain entity.
9. Define the authoritative owner and transaction boundaries for financial state.
10. Use integer minor units or BigDecimal with explicit currency and rounding rules.
11. Never use float or double for financial amounts.
12. Use immutable financial records and auditable corrections.
13. Make subscription changes, invoice finalization, payment collection, refunds, and webhook processing idempotent.
14. Use bounded retries, timeouts, concurrency, queues, and connection pools.
15. Never blindly retry an external financial operation after an ambiguous timeout.
16. Do not assume external payment providers support all recurring-payment operations or universal idempotency.
17. Keep payment credentials and provider secrets out of source code, databases where unnecessary, and logs.
18. Distinguish invoice creation, payment authorization, capture, settlement, and reconciliation.
19. Do not fabricate tests, throughput measurements, or production-readiness claims.
20. Deliver working software, not just HLD, LLD, pseudocode, empty modules, or UI mockups.

**The end goal is a runnable, maintainable, financial-correctness-oriented subscription billing product with complete backend, frontend, tests, infrastructure, and documentation.**

---

# PART A — PRODUCT VISION

## 2. PRIMARY OBJECTIVE

Build an enterprise-grade Subscription Billing Platform suitable for:

- SaaS subscriptions
- Video streaming subscriptions
- Developer API subscriptions
- Cloud infrastructure subscriptions
- Enterprise software licensing
- Membership platforms
- Subscription-based e-commerce

The system must support:

1. Customer registration and authentication
2. Organizations and tenant accounts
3. Product catalog
4. Pricing plans
5. Monthly subscriptions
6. Quarterly subscriptions
7. Annual subscriptions
8. Custom recurring billing periods
9. Free trials
10. Paid trials where justified
11. Subscription activation
12. Subscription renewal
13. Subscription pause and resume
14. Plan upgrades
15. Plan downgrades
16. Quantity/seat changes
17. Subscription cancellation
18. Proration
19. Usage-based billing
20. Coupons and discounts
21. Billing-cycle calculation
22. Invoice generation
23. Invoice finalization
24. Tax calculation
25. Payment authorization/collection
26. Automatic recurring payments
27. Payment retries
28. Dunning workflows
29. Grace periods
30. Payment method updates
31. Refunds
32. Credit notes
33. Customer account balances
34. Financial ledger
35. Payment reconciliation
36. Subscription entitlements
37. Notifications
38. Financial reporting
39. Customer self-service portal
40. Finance and administration portal

---

# PART B — END-TO-END BILLING SCENARIO

## 3. EXAMPLE SUBSCRIPTION WORKFLOW

A SaaS company offers:

**Starter Plan:** ₹499/month

**Professional Plan:** ₹1,499/month

**Enterprise Plan:** Custom annual pricing

A customer subscribes to the Professional Plan with a seven-day trial.

The platform must:

1. Authenticate the customer.
2. Validate plan eligibility.
3. Create an idempotent subscription request.
4. Record the selected plan and immutable price version.
5. Establish the billing anchor and time zone policy.
6. Create a trial subscription.
7. Collect a payment method through an approved provider flow where supported.
8. Schedule the trial-ending workflow durably.
9. Notify the customer before trial expiry according to policy.
10. Transition from TRIALING to an appropriate payment/active state.
11. Generate a draft invoice for the first paid period.
12. Calculate line items, discounts, and tax.
13. Finalize the invoice.
14. Attempt collection through the payment provider.
15. Process provider responses and webhooks.
16. Mark the invoice paid only after verified payment state.
17. Activate the subscription entitlement.
18. Schedule the next billing cycle.
19. Process renewals automatically.
20. Handle payment failures through bounded retries and dunning.
21. Support plan changes and prorations.
22. Reconcile provider payments against invoices and financial records.
23. Maintain a complete audit history.

## Fundamental correctness invariants

- One logical billing cycle must not generate multiple unintended invoices.
- An invoice number must be unique within the defined legal entity/numbering scope.
- Finalized financial document amounts must not be silently mutated.
- Duplicate requests and webhooks must not create duplicate payment effects.
- A subscription renewal must not be lost because a scheduler crashes.
- Payment uncertainty must not be interpreted automatically as failure.
- Invoice balance calculations must include verified payments, credits, refunds, and adjustments consistently.
- A subscription cannot transition into an invalid state.
- Entitlements must follow documented subscription/payment policies.
- Financial records must remain auditable after cancellations, credits, and corrections.
- A payment can be retried only under the verified provider contract and its idempotency guarantees.

---

# PART C — FUNCTIONAL REQUIREMENTS

## 4. CUSTOMER AND ORGANIZATION MANAGEMENT

Implement:

- Customer registration
- Customer profile
- Organization account
- Billing contact
- Billing address
- Tax identifiers
- Currency preference
- Locale
- Time zone
- Notification preferences
- Payment method references
- Account closure
- Data export
- Data retention/deletion according to policy

Support B2C and B2B subscriptions.

Define which domain concepts belong to the customer versus the billing account.

---

## 5. PRODUCT CATALOG

Implement:

- Product creation
- Product versioning
- Product activation/deactivation
- Product description
- Product category
- Billing eligibility
- Subscription entitlements
- Features and limits
- Product archival

Example products:

- Developer API Platform
- Video Streaming Premium
- SaaS Professional Suite

Do not treat a product and its price as the same entity.

---

## 6. PRICING PLANS

Support:

- Free
- Monthly
- Quarterly
- Annual
- Fixed recurring
- Per-seat
- Tiered pricing
- Usage-based pricing
- Hybrid base-fee plus usage
- Custom enterprise plans

Define:

- Price ID
- Product ID
- Currency
- Amount
- Billing interval
- Interval count
- Billing model
- Tax behavior
- Price version
- Effective date
- Active status

### Critical requirement

Existing subscriptions must preserve their contractual price semantics when the public price catalog changes.

Use immutable price versions or explicit subscription price snapshots.

---

# PART D — SUBSCRIPTION LIFECYCLE

## 7. SUBSCRIPTION STATE MACHINE

Design a coherent state machine.

Possible states:

- INCOMPLETE
- TRIALING
- ACTIVE
- PAST_DUE
- PAUSED
- UNPAID
- CANCELLATION_SCHEDULED
- CANCELLED
- EXPIRED

Define exact semantics and legal transitions.

Do not blindly reproduce a provider's state names as the platform's domain model.

### Operations

- Create
- Activate
- Pause
- Resume
- Renew
- Upgrade
- Downgrade
- Change quantity
- Cancel immediately
- Cancel at period end
- Reactivate when permitted

Every state-changing command must be idempotent or explicitly reject duplicates.

---

## 8. TRIAL MANAGEMENT

Support:

- Free trial
- Trial start/end timestamps
- Trial eligibility
- Trial extension
- Trial expiration
- Trial-to-paid conversion
- Trial cancellation
- Trial notification

Prevent unintended repeated trial activation.

Define whether a payment method is required before trial starts.

Implement durable trial-ending jobs.

---

## 9. PLAN UPGRADES AND DOWNGRADES

Support:

- Immediate upgrade
- Scheduled downgrade
- Change at renewal
- Prorated change
- Full-period billing policy
- Plan-change audit history

### Critical scenario

A customer upgrades from ₹499/month to ₹1,499/month halfway through the billing period.

The platform must calculate the unused old-plan credit and new-plan charge using a documented method.

Do not assume all billing systems use the same prorating convention.

---

# PART E — BILLING CYCLES

## 10. RECURRING BILLING ENGINE

Implement:

- Billing anchors
- Billing intervals
- Next billing date
- Period start/end
- Billing schedule
- Renewal generation
- Calendar boundary handling
- Subscription anniversary rules
- Billing cutoffs
- Billing timezone policy

### Required calendar scenarios

- January 31 monthly subscription
- February 28/29
- Leap year
- Daylight saving transitions
- End-of-month renewal
- Quarterly renewal
- Annual renewal
- Customer time-zone changes

Define whether recurrence uses UTC instant anchors, local calendar dates, or a hybrid.

Do not add a fixed 30 days to represent one calendar month.

Use Java 21 `java.time`.

---

## 11. BILLING SCHEDULER

Implement durable recurring billing.

Compare:

- Spring Scheduler
- Quartz
- PostgreSQL job table
- Kafka-triggered processing
- Distributed scheduler

Use a durable scheduling strategy appropriate to the workload.

### Scheduler requirements

- Atomic job claims
- Lease expiration
- Worker crash recovery
- Bounded retries
- Next-run timestamp
- Job status
- Idempotency
- Backpressure
- Horizontal scaling
- Reconciliation

### Critical scenario

Three Spring Boot replicas attempt to bill the same subscription at midnight.

Only one logical billing-cycle operation should be committed.

Use database-enforced uniqueness on subscription and billing-period identity.

Do not rely on `@Scheduled` alone as distributed coordination.

---

# PART F — PRORATION

## 12. PRORATION ENGINE

Compare:

- Time-based proration
- Day-based proration
- Second-based proration
- Immediate upgrade charge
- Credit next invoice
- No proration
- Scheduled change

Implement configurable, deterministic policies.

### Example calculation

Existing plan: ₹499/month.

New plan: ₹1,499/month.

Half of the billing period remains.

Conceptual calculation:

`Proration = New Plan Charge for Remaining Period − Old Plan Credit for Unused Period`

Using an idealized 50% remaining period:

- New charge: ₹749.50
- Old credit: ₹249.50
- Net adjustment: ₹500.00

This is an illustrative calculation.

Final implementation must account for:

- Exact billing-period boundaries
- Rounding
- Currency
- Discounts
- Tax
- Billing-specific policy
- Previous credits
- Multiple changes in one period

Never use floating-point monetary arithmetic.

---

## 13. PRORATION EDGE CASES

Handle:

1. Upgrade immediately after renewal.
2. Upgrade one second before renewal.
3. Downgrade mid-cycle.
4. Multiple plan changes.
5. Quantity increase.
6. Quantity decrease.
7. Proration plus coupon.
8. Proration with tax.
9. Proration across currency restrictions.
10. Cancellation with unused prepaid time.

Define deterministic invoice-line generation.

Do not overwrite historical finalized invoice lines to represent new adjustments.

---

# PART G — USAGE-BASED BILLING

## 14. USAGE METERING

Support billable usage for:

- API calls
- Storage consumption
- Compute usage
- Seats
- Transaction counts
- Message delivery counts

Implement:

- Usage event ingestion
- Event ID
- Customer/account ID
- Meter ID
- Quantity
- Event timestamp
- Billing-period attribution
- Deduplication
- Corrections
- Late events
- Aggregation

Distinguish raw telemetry from billable usage.

Do not bill using approximate application metrics without a durable metering contract.

---

## 15. USAGE AGGREGATION

Support:

- Sum
- Maximum
- Last observed value
- Unique count where contractually applicable
- Tiered rates
- Volume pricing
- Graduated pricing

Define exact semantics for each meter.

Handle:

- Duplicate events
- Out-of-order events
- Late-arriving usage
- Corrections after invoice finalization

Use adjustment invoices or credit notes rather than silently modifying finalized documents.

---

# PART H — INVOICE MANAGEMENT

## 16. INVOICE LIFECYCLE

Define:

- DRAFT
- OPEN
- PAYMENT_PENDING
- PAID
- PARTIALLY_PAID
- PAST_DUE
- VOID
- UNCOLLECTIBLE

Use a coherent domain state machine.

Support:

- Invoice generation
- Line items
- Discounts
- Taxes
- Credits
- Invoice finalization
- Invoice numbering
- Due date
- PDF rendering
- Payment allocation
- Invoice history
- Downloadable invoices
- Credit notes
- Voiding
- Write-offs under authorized policy

---

## 17. INVOICE FINANCIAL MODEL

Define:

- Invoice ID
- Invoice number
- Customer/account
- Currency
- Issue date
- Due date
- Billing period
- Line items
- Subtotal
- Discounts
- Tax
- Total
- Amount paid
- Credits applied
- Amount remaining
- Status
- Version

### Fundamental invoice invariant

`Invoice Balance = Finalized Total − Applied Payments − Applied Credits + Valid Adjustments`

Define adjustments and refunds consistently; do not mix cash refunds with invoice credits without an accounting policy.

Use immutable financial document line items after finalization.

Corrections must be auditable.

---

## 18. INVOICE NUMBERING

Design:

- Unique number per legal entity
- Number sequence policy
- Concurrency-safe issuance
- Fiscal period
- Immutable finalized number
- Audit history

Do not promise gapless numbering unless the system design and jurisdictional requirements can support it.

Document legal requirements as deployment-specific.

---

## 19. PDF INVOICE GENERATION

Implement:

- Invoice template
- Company branding
- Tax information
- Billing details
- Line items
- Totals
- Payment references
- Credit-note links where applicable

Store generated artifacts in S3-compatible object storage.

Provide secured download URLs.

Do not expose invoices belonging to another customer.

---

# PART I — TAXATION

## 20. TAX ENGINE

Implement a pluggable tax calculation boundary.

Support:

- Tax-inclusive pricing
- Tax-exclusive pricing
- Tax exemptions
- Region-dependent rates
- B2B/B2C treatment
- Tax identifiers
- Tax snapshots
- Rounding
- Credit-note adjustments

Use a deterministic local tax simulator.

Optionally integrate an approved tax provider through a verified adapter.

Do not hardcode country-specific tax rules as globally correct.

Document that legal/tax compliance requires jurisdiction-specific review.

---

# PART J — PAYMENT PROVIDER INTEGRATION

## 21. PAYMENT ABSTRACTION

Implement a provider port supporting relevant operations:

- Customer/payment-method reference creation
- Recurring mandate/setup
- Payment authorization
- Capture
- Charge/payment collection
- Status lookup
- Void/cancellation
- Refund
- Webhook processing

Choose an approved sandbox provider such as Stripe or Razorpay, depending on the deployment's region and required recurring-payment capabilities.

Do not assume provider APIs expose identical operations.

A provider adapter must explicitly declare supported capabilities.

Use a deterministic local simulator as the default runnable implementation.

---

## 22. RECURRING PAYMENT FLOW

Implement:

1. Resolve due finalized invoice.
2. Check payment collection eligibility.
3. Create a durable payment attempt.
4. Assign a stable idempotency identity.
5. Call the provider with a bounded timeout.
6. Persist the known result.
7. Process delayed webhook/status updates.
8. Reconcile ambiguous outcomes.
9. Apply verified payments to invoices.
10. Emit integration events.
11. Update subscription entitlement according to policy.

### Critical rule

Payment collection, invoice payment status, provider settlement, and subscription entitlement are distinct state transitions.

Do not mark a subscription financially settled merely because the provider accepted a request.

---

# PART K — IDEMPOTENCY

## 23. IDEMPOTENCY MODEL

Implement idempotency for:

- Subscription creation
- Plan changes
- Billing-cycle processing
- Invoice finalization
- Payment initiation
- Refund creation
- Credit-note issuance
- Webhook processing
- Manual billing retries

Use a scoped operation identity.

Example:

`(tenant_id, operation_type, idempotency_key)`

Persist:

- Request fingerprint
- Operation identity
- Status
- Result reference
- Created timestamp
- Expiration policy

### Required behavior

- Same key and same payload returns the existing logical result.
- Same key and different payload returns a conflict.
- Concurrent requests create one logical operation.
- A lost HTTP response can be recovered.
- Retries across application instances remain safe.
- Expired idempotency records cannot cause unsafe duplication of long-lived financial operations.

Use durable database constraints where correctness requires them.

Do not rely solely on Redis SETNX.

---

## 24. BILLING CYCLE UNIQUENESS

Define a unique business identity for a renewal.

Example:

`(subscription_id, billing_period_start, billing_period_end, cycle_type)`

Enforce uniqueness.

A process crash or Kafka replay must not create a second unintended invoice for the same billing cycle.

---

# PART L — RETRY AND DUNNING

## 25. PAYMENT RETRY ENGINE

Implement:

- Retry eligibility
- Maximum attempts
- Exponential backoff
- Jitter
- Provider-specific retry rules
- Retry-After where supported
- Payment attempt history
- Overall retry window
- Explicit manual-retry controls

### Retry classification

Distinguish:

- Transient network error
- Provider rate limit
- Temporary provider failure
- Declined payment
- Authentication required
- Expired payment method
- Invalid mandate
- Unknown provider outcome

Do not automatically retry hard declines or ambiguous charges without verified provider semantics.

---

## 26. DUNNING MANAGEMENT

Support:

- Payment-failed notification
- Reminder email
- Grace period
- Payment method update
- Scheduled retry
- Final reminder
- Subscription restriction
- Pause/suspension
- Cancellation
- Recovery after successful payment

Define a configurable dunning policy.

Example:

- Day 0: Payment failure notification
- Day 2: Retry when eligible
- Day 5: Reminder
- Day 7: Final retry
- Day 10: Restrict service according to policy

Treat these timings as examples.

Dunning must not issue duplicate reminders or charges due to scheduler redelivery.

---

# PART M — SUBSCRIPTION ENTITLEMENTS

## 27. ENTITLEMENT ENGINE

Implement:

- Product features
- Seat limits
- Usage limits
- Plan entitlements
- Trial entitlements
- Grace-period entitlements
- Suspended entitlements
- Revoked entitlements

Separate billing status from service access policy.

Example:

A PAST_DUE subscription may retain service access during a configured grace period.

Do not assume every payment failure requires immediate access revocation.

Provide an API for downstream SaaS services to query or receive entitlement changes.

---

# PART N — REFUNDS AND CREDIT NOTES

## 28. REFUND MANAGEMENT

Implement:

- Full refunds
- Partial refunds
- Refund eligibility
- Provider refund requests
- Refund status
- Refund idempotency
- Refund history
- Refund reconciliation

Prevent refunds exceeding the eligible refundable amount.

Use database concurrency controls.

---

## 29. CREDIT NOTES

Implement:

- Credit-note creation
- Link to original invoice
- Credit amount
- Reason
- Tax adjustments
- Customer balance handling
- Audit history

Distinguish credit notes from cash refunds.

Do not treat every credit as a payment provider refund.

---

# PART O — FINANCIAL LEDGER

## 30. AUDITABLE ACCOUNTING

Design a ledger or accounting subsystem appropriate to the platform's financial scope.

Prefer immutable financial movements.

Evaluate double-entry bookkeeping for:

- Accounts receivable
- Customer credits
- Cash/processor clearing
- Refund liabilities
- Deferred revenue where applicable
- Recognized revenue
- Taxes payable

Do not confuse billing events with bank settlement.

### Required invariants

- Entries are immutable once posted.
- Corrections use reversing or adjusting entries.
- Posting is idempotent.
- Financial amounts are currency-safe.
- Posting groups balance under the selected accounting model.
- Ledger totals can be reconciled to source invoices/payments.

Provide clear examples of journal entries.

Do not claim full accounting or revenue-recognition compliance without professional review.

---

# PART P — RECONCILIATION

## 31. RECONCILIATION ENGINE

Implement reconciliation between:

- Subscription billing cycles
- Finalized invoices
- Payment attempts
- Provider payments
- Provider settlements
- Refunds
- Customer balances
- Ledger movements

Detect:

- Missing invoices
- Duplicate invoices
- Payment succeeded but invoice not updated
- Invoice paid but provider payment missing
- Provider payment exists without matching invoice
- Duplicate refund records
- Settlement amount mismatch
- Stuck payment attempts
- Unapplied customer credits
- Out-of-balance ledger postings

---

## 32. RECONCILIATION WORKFLOW

Implement:

1. Load source records.
2. Normalize provider identifiers and currency.
3. Match records by stable references.
4. Compare amounts and states.
5. Identify discrepancies.
6. Create durable reconciliation tasks.
7. Classify recoverable versus manual-review issues.
8. Apply safe idempotent corrections.
9. Record audit evidence.
10. Expose reconciliation status.

Never silently adjust financial balances to make reports agree.

---

# PART Q — DISTRIBUTED TRANSACTIONS

## 33. SAGA AND BUSINESS WORKFLOWS

Evaluate Saga orchestration for cross-service workflows such as:

`Subscription Created → Invoice Finalized → Payment Collected → Entitlement Activated`

Use durable coordination only where service boundaries justify it.

Do not implement one global database transaction across independently owned services.

Compensations must be explicit.

Examples:

- Payment failed: keep invoice open and enter dunning.
- Invoice voided: reverse appropriate pending state.
- Payment captured but subscription activation failed: reconcile and retry entitlement update.
- Cancellation after payment: apply refund/credit policy.

Do not assume all external financial operations are reversible.

---

# PART R — TRANSACTIONAL OUTBOX AND INBOX

## 34. RELIABLE EVENT PUBLICATION

Implement transactional outbox for integration events that must accompany committed database state.

Possible events:

- SubscriptionCreated
- SubscriptionActivated
- SubscriptionRenewed
- SubscriptionUpgraded
- SubscriptionDowngraded
- SubscriptionCancelled
- InvoiceDrafted
- InvoiceFinalized
- InvoicePaid
- InvoicePastDue
- PaymentAttempted
- PaymentSucceeded
- PaymentFailed
- PaymentOutcomeUnknown
- RefundCreated
- RefundCompleted
- DunningStarted
- EntitlementChanged
- ReconciliationDiscrepancyDetected

Use versioned event contracts.

---

## 35. IDEMPOTENT CONSUMPTION

Use an inbox/processed-event store or equivalent durable deduplication where state changes require it.

Handle:

- Duplicate messages
- Out-of-order delivery
- Consumer rebalance
- Worker crash
- Kafka outage
- Poison messages
- Dead-letter processing
- Replay

Do not claim Kafka exactly-once guarantees exactly-once PostgreSQL and payment-provider effects.

---

# PART S — HLD ARCHITECTURE

## 36. COMPARE ARCHITECTURE OPTIONS

### Option A — Modular Monolith

One Spring Boot application containing bounded modules.

### Option B — Pragmatic Microservices

Separate independently owned billing, payments, entitlements, and reporting workloads.

### Option C — Event-Driven Billing Platform

Durable billing engine, scheduler, payment workers, event-driven projections, and financial reconciliation.

### Option D — Multi-Region Enterprise Billing

Regional services with one authoritative ownership strategy for financial operations.

Evaluate:

- Financial correctness
- Complexity
- Latency
- Throughput
- Reliability
- Team ownership
- Deployment independence
- Operational cost

### Recommended baseline

Implement a modular Spring Boot 4.x backend with independently scalable billing/payment workers where required.

Use clear module boundaries and an explicit path to microservice extraction.

---

## 37. REFERENCE COMPONENTS

Consider:

1. Customer Service
2. Product and Pricing Catalog
3. Subscription Management
4. Billing Calendar Engine
5. Billing Scheduler
6. Invoice Service
7. Tax Calculation
8. Payment Orchestrator
9. Payment Provider Adapter
10. Retry and Dunning Engine
11. Refund Service
12. Credit Note Service
13. Entitlement Service
14. Ledger
15. Reconciliation Service
16. Notification Worker
17. Reporting and Analytics
18. Administration API
19. PostgreSQL
20. Redis
21. Kafka
22. Object Storage
23. Next.js Applications
24. Observability

Provide Mermaid diagrams for:

- Overall HLD
- Subscription lifecycle
- Trial conversion
- Renewal billing
- Invoice generation
- Payment processing
- Dunning
- Plan upgrade/proration
- Refund
- Ledger posting
- Reconciliation
- Worker crash recovery
- Kubernetes deployment

---

# PART T — DATABASE DESIGN

## 38. POSTGRESQL SCHEMA

Design tables such as:

- customers
- billing_accounts
- products
- product_prices
- pricing_tiers
- subscriptions
- subscription_items
- subscription_versions
- subscription_changes
- trial_periods
- billing_cycles
- billing_jobs
- invoices
- invoice_line_items
- invoice_tax_lines
- invoice_payments
- payment_methods
- payment_intents
- payment_attempts
- provider_events
- payment_mandates
- refunds
- credit_notes
- customer_credits
- usage_meters
- usage_events
- usage_aggregates
- dunning_policies
- dunning_attempts
- entitlements
- ledger_accounts
- journal_entries
- journal_lines
- reconciliation_runs
- reconciliation_discrepancies
- outbox_events
- processed_events
- audit_events

Create only the tables justified by the implemented scope.

Use Flyway.

Define:

- Primary keys
- Foreign keys
- Unique constraints
- Check constraints
- Optimistic versions
- Indexes
- Transaction isolation
- Tenant ownership
- Retention
- Partitioning where needed

### Mandatory constraints

- Unique logical billing cycle
- Unique finalized invoice number within scope
- Unique payment operation identity
- Unique provider event identity within provider/account scope
- No invalid financial amounts
- No duplicate ledger posting
- No excessive refund allocation
- Valid subscription transitions
- No cross-tenant financial access

---

# PART U — REST API DESIGN

## 39. REQUIRED APIS

### Products and Plans

- `GET /api/v1/products`
- `GET /api/v1/plans`
- `GET /api/v1/plans/{planId}`
- `POST /api/v1/admin/plans`
- `PATCH /api/v1/admin/plans/{planId}`

### Subscriptions

- `POST /api/v1/subscriptions`
- `GET /api/v1/subscriptions`
- `GET /api/v1/subscriptions/{id}`
- `POST /api/v1/subscriptions/{id}/upgrade`
- `POST /api/v1/subscriptions/{id}/downgrade`
- `POST /api/v1/subscriptions/{id}/pause`
- `POST /api/v1/subscriptions/{id}/resume`
- `POST /api/v1/subscriptions/{id}/cancel`

### Billing

- `GET /api/v1/billing/overview`
- `GET /api/v1/billing/cycles`
- `POST /api/v1/billing/preview`
- `GET /api/v1/invoices`
- `GET /api/v1/invoices/{id}`
- `GET /api/v1/invoices/{id}/pdf`

### Payments

- `POST /api/v1/payments/setup`
- `POST /api/v1/payments/{paymentId}/retry`
- `GET /api/v1/payments/{paymentId}`
- `POST /api/v1/payments/{paymentId}/refunds`

### Usage

- `POST /api/v1/usage/events`
- `GET /api/v1/usage/summary`

### Customer Portal

- `GET /api/v1/customers/me/billing`
- `GET /api/v1/customers/me/payment-methods`
- `GET /api/v1/customers/me/entitlements`

### Provider Webhooks

- `POST /api/v1/webhooks/payments/{provider}`

### Admin/Finance

- `GET /api/v1/admin/subscriptions`
- `GET /api/v1/admin/invoices`
- `GET /api/v1/admin/payment-attempts`
- `GET /api/v1/admin/dunning`
- `GET /api/v1/admin/reconciliation`
- `POST /api/v1/admin/reconciliation/run`
- `GET /api/v1/admin/ledger`
- `GET /api/v1/admin/reports/revenue`

For every endpoint define:

- Authentication
- Resource-level authorization
- Request/response contracts
- Idempotency
- Validation
- Status codes
- Error contracts
- Pagination
- Audit behavior
- OpenAPI documentation

Use RFC 9457 Problem Details where applicable.

---

# PART V — NEXT.JS FRONTEND

## 40. UI/UX PRODUCT VISION

Build a premium SaaS subscription billing experience influenced by modern financial SaaS dashboards.

Use an original visual identity.

The interface must be:

- Clean
- Professional
- Responsive
- Accessible
- Fast
- Easy to navigate
- Transparent about billing amounts and dates
- Clear about payment failures
- Safe for destructive financial actions

Do not create a generic collection of static dashboard cards.

All pages must use real backend APIs.

---

## 41. CUSTOMER BILLING PORTAL

Implement:

### Pricing Page

- Plan cards
- Monthly/annual toggle
- Feature comparison
- Trial details
- Price breakdown
- Subscribe action

### Subscription Overview

- Current plan
- Subscription status
- Billing period
- Renewal date
- Next estimated charge
- Trial expiration
- Entitlements
- Upgrade/downgrade controls

### Plan Change

- Compare plans
- Calculate proration preview
- Show immediate versus future charges
- Confirm changes
- Display resulting billing dates

### Payment Methods

- View saved provider-managed payment method references
- Add/update payment method through approved provider flow
- Remove method where allowed
- View collection failures

### Invoices

- Invoice list
- Status
- Billing period
- Amount
- Due date
- Download PDF
- Payment status

### Billing History

- Successful payments
- Failed attempts
- Credits
- Refunds
- Receipts

### Cancellation

- Cancel immediately or at period end
- Show entitlement effects
- Explain outstanding invoices
- Confirmation dialog
- Cancellation acknowledgment

### Usage Dashboard

- Included usage
- Current usage
- Forecasted charges where supported
- Overage details
- Meter breakdown

---

## 42. ADMIN AND FINANCE PORTAL

Implement:

- Customer search
- Subscription management
- Plan management
- Invoice management
- Payment attempt inspection
- Dunning queue
- Failed payment recovery
- Refund administration
- Credit notes
- Ledger inspection
- Reconciliation runs
- Financial discrepancy review
- Revenue and collections dashboards
- Tax configuration
- Billing policy configuration
- Feature flags
- Audit logs

Administrative actions must enforce backend RBAC.

---

## 43. DESIGN SYSTEM

Use:

- Next.js App Router
- React
- TypeScript
- Tailwind CSS
- Accessible component primitives
- TanStack Query where justified
- React Hook Form
- Zod
- A maintained charting library

Design:

- Responsive navigation
- Clear financial tables
- Status indicators
- Currency formatting
- Date formatting
- Loading skeletons
- Empty states
- Form errors
- Confirmation dialogs
- Accessible tables
- Keyboard interaction
- Focus management
- Mobile layouts
- Reduced-motion behavior

Target WCAG 2.2 AA where practical and verify actual implementation.

### Financial UX rules

- Never hide fees in a confirmation screen.
- Show exact charge timing.
- Distinguish next invoice estimate from finalized amount.
- Distinguish trial end from first payment date.
- Explain prorations before confirmation.
- Avoid misleading success states while payments remain pending.
- Prevent duplicate submissions through request identity, not UI disabling alone.

---

# PART W — SECURITY

## 44. ENTERPRISE SECURITY REQUIREMENTS

Implement:

1. OAuth2/OIDC
2. Spring Security
3. Customer authorization
4. Tenant isolation
5. Admin and finance RBAC
6. Secure payment provider integration
7. Webhook signature validation
8. Webhook replay protection
9. TLS
10. Secrets management
11. Secure payment method references
12. Rate limiting
13. Input validation
14. Audit logging
15. Invoice access control
16. Sensitive data minimization
17. Tax identifier protection
18. Secure PDF delivery
19. Transaction authorization
20. Administrative approval for sensitive financial corrections where justified

Do not store CVV or unrestricted raw card credentials.

Use approved provider-hosted payment collection flows.

---

# PART X — SIX AWS WELL-ARCHITECTED PILLARS

## 45. OPERATIONAL EXCELLENCE

Implement:

- CI/CD
- Automated migrations
- Financial runbooks
- Billing scheduler monitoring
- Dunning alerts
- Reconciliation dashboards
- Incident response
- Safe deployments
- Feature flags
- Audit history

## 46. SECURITY

Implement:

- IAM/RBAC
- Least privilege
- Encryption
- Protected payment references
- Secrets management
- Tenant isolation
- Webhook verification
- Financial audit controls
- Data retention
- Security testing

## 47. RELIABILITY

Implement:

- Durable billing jobs
- Idempotency
- Outbox/Inbox
- Multi-AZ design
- Worker crash recovery
- Provider timeout reconciliation
- Financial record durability
- Backups
- Restore procedures
- Disaster recovery design

## 48. PERFORMANCE EFFICIENCY

Implement:

- Efficient billing-cycle queries
- Database indexes
- Batch invoice processing
- Queue-driven workers
- Bounded concurrency
- Cache appropriate reference data
- Connection pool tuning
- Query optimization
- Load testing

## 49. COST OPTIMIZATION

Evaluate:

- Managed databases
- Worker scaling
- Kafka cost
- Payment provider fees
- Retry cost
- Object storage lifecycle
- Reporting aggregation
- Database growth
- Multi-region trade-offs

## 50. SUSTAINABILITY

Consider:

- Efficient scheduled processing
- Avoided duplicate retries
- Batched billing jobs
- Autoscaling
- Reduced idle compute
- Data retention controls
- Efficient storage
- Avoided repeated invoice rendering

Create a six-pillar review matrix identifying design decisions, trade-offs, verification, and risks.

---

# PART Y — PRODUCTION FAILURE SCENARIOS

## 51. ANALYZE THESE 50 SCENARIOS

1. Two scheduler instances bill the same subscription concurrently.
2. Billing job is claimed but the worker crashes.
3. Billing job commits an invoice but loses acknowledgment.
4. Customer subscription renews at month end.
5. Billing cycle crosses a leap-year boundary.
6. Daylight saving changes billing timestamps.
7. Plan changes occur during invoice generation.
8. Customer upgrades twice concurrently.
9. Plan downgrade races with renewal.
10. Coupon expires during invoice finalization.
11. Tax rate changes during billing.
12. Usage data arrives late.
13. Same usage event is delivered twice.
14. Usage correction arrives after invoice finalization.
15. Invoice is finalized twice.
16. Invoice PDF generation fails.
17. Payment authorization succeeds but HTTP response times out.
18. Payment provider captures payment but webhook is delayed.
19. Payment webhook arrives twice.
20. Payment webhook arrives before local API response.
21. Provider returns inconsistent statuses.
22. Payment method expires.
23. Recurring mandate is revoked.
24. Payment requires additional authentication.
25. Provider rate-limits payment collection.
26. Retry job executes twice.
27. Payment succeeds while dunning is in progress.
28. Dunning reminder is sent twice.
29. Subscription cancellation races with payment collection.
30. Trial expires while customer cancels.
31. Refund is initiated twice.
32. Two refunds exceed the captured amount.
33. Refund succeeds but response is lost.
34. Credit note is created after an invoice has been paid.
35. Customer credit is applied twice.
36. Entitlement update fails after successful payment.
37. Subscription becomes PAST_DUE during a service outage.
38. PostgreSQL primary fails.
39. Kafka becomes unavailable.
40. Redis becomes unavailable.
41. Payment provider remains unavailable for several hours.
42. Ledger posting is duplicated.
43. Ledger transaction becomes unbalanced.
44. Reconciliation finds a provider payment without an invoice.
45. Provider settlement differs from recorded collections.
46. A customer views another tenant's invoice.
47. Financial audit logs accidentally expose payment data.
48. Rolling deployment changes event schemas.
49. Multi-region workers attempt the same billing job.
50. A regional outage occurs during a major renewal batch.

For each scenario provide:

- Root cause
- Financial correctness impact
- Customer impact
- Data consistency risk
- Detection
- Prevention
- Recovery
- Automated test
- Residual risk

---

# PART Z — TESTING STRATEGY

## 52. UNIT TESTS

Cover:

- Billing date calculation
- Monthly/quarterly/annual recurrence
- Leap year
- End-of-month rules
- Time zones
- Subscription state transitions
- Trial conversion
- Pricing
- Proration
- Tax calculation
- Discount application
- Invoice totals
- Payment allocation
- Dunning eligibility
- Refund limits
- Ledger balancing
- Reconciliation matching

Use deterministic clocks and test fixtures.

---

## 53. POSTGRESQL INTEGRATION TESTS

Use Testcontainers.

Verify:

- Flyway migrations
- Unique billing cycles
- Concurrent invoice generation
- Idempotency constraints
- Invoice finalization
- Payment attempt persistence
- Refund limits
- Financial transaction boundaries
- Outbox persistence
- Ledger posting
- Reconciliation
- Tenant isolation

Do not rely on H2 or mocked repositories to establish PostgreSQL concurrency correctness.

---

## 54. KAFKA INTEGRATION TESTS

Verify:

- Outbox publication
- Consumer deduplication
- Duplicate webhooks
- Event replay
- Out-of-order events
- Dead-letter handling
- Consumer crash recovery
- Billing event processing

---

## 55. CONCURRENCY TESTS

Verify:

- Two workers renew one subscription.
- Concurrent same-key checkout.
- Plan change versus renewal.
- Cancellation versus renewal.
- Payment success versus retry.
- Refund versus another refund.
- Credit allocation races.
- Duplicate ledger posting.
- Multiple reconciliation workers.

Assert database and accounting invariants, not only HTTP status codes.

---

## 56. PAYMENT SIMULATOR TESTS

Create deterministic cases for:

- Success
- Failure
- Hard decline
- Soft decline
- Timeout
- Accepted but response lost
- Webhook delayed
- Webhook duplicated
- Webhook out of order
- Refund succeeded but response lost
- Provider outage

Do not run live financial transactions in automated load or chaos tests.

---

## 57. FRONTEND TESTS

Use:

- TypeScript checking
- ESLint
- Component tests
- Playwright
- Accessibility checks

Verify:

- Pricing plan selection
- Subscription creation
- Trial display
- Upgrade/downgrade
- Proration preview
- Invoice list
- Payment method update
- Dunning notification
- Cancellation
- Refund status
- Admin reconciliation
- Tenant authorization
- Mobile responsive behavior

---

# PART AA — PERFORMANCE ENGINEERING

## 58. CAPACITY TARGETS

Use illustrative targets:

| Dimension | Target |
|---|---|
| Customers | 10 million |
| Active subscriptions | 5 million |
| Daily billing operations | 1 million |
| Peak renewal jobs | 10,000/sec design scenario |
| Invoices/day | 1 million |
| Usage records/day | 100 million |
| Availability | 99.99% design target |
| Financial correctness | Enforced under documented transaction/consistency model |
| Deployment | Multi-AZ Kubernetes |
| Multi-region | Future extension |

Calculate:

- Scheduled renewals/sec
- Invoice writes/sec
- Payment provider operations/sec
- Kafka events/sec
- Usage aggregation throughput
- Ledger storage
- Invoice PDF storage
- Reconciliation workload
- Database connections
- Worker concurrency
- Retry amplification
- Infrastructure cost

Distinguish billing jobs from successful payment collections.

Do not assume every billing job results in one immediate payment.

---

## 59. LOAD TESTS

Create reproducible workloads for:

- 100 renewals/sec
- 1,000 renewals/sec
- 5,000 renewals/sec
- 10,000 renewals/sec as a scale target

Benchmark separately:

- Subscription creation
- Billing-cycle calculation
- Invoice generation
- Invoice finalization
- Payment orchestration using simulator
- Usage aggregation
- Dunning scheduling
- Ledger posting
- Reconciliation

Measure:

- Offered/achieved TPS
- p50/p95/p99 latency
- Billing throughput
- Duplicate invoice violations
- Payment duplication violations
- Database lock waits
- Deadlocks
- Kafka lag
- Scheduler backlog
- Retry amplification
- Reconciliation discrepancies
- JVM CPU/heap/GC
- Database connection utilization

Never fabricate benchmark results.

---

# PART AB — OBSERVABILITY

## 60. METRICS AND ALERTING

Use Micrometer, OpenTelemetry, Prometheus, and Grafana.

Track:

- Active subscriptions
- Trials ending
- Renewals due
- Renewal success/failure
- Billing job backlog
- Duplicate billing attempts
- Invoice generation duration
- Invoice finalization failures
- Payment attempts
- Payment authorization failures
- Ambiguous payment outcomes
- Dunning backlog
- Refund failures
- Ledger posting failures
- Out-of-balance transaction violations
- Reconciliation mismatches
- Payment provider latency
- Kafka consumer lag
- Database saturation
- API p95/p99 latency

Avoid high-cardinality customer, invoice, subscription, and payment IDs as metric labels.

Use secured traces and logs for individual financial-operation investigations.

---

# PART AC — PROJECT STRUCTURE

## 61. RECOMMENDED MONOREPO

```text
subscription-billing-platform/
├── README.md
├── docs/
│   ├── requirements/
│   ├── architecture/
│   ├── domain-model/
│   ├── billing-engine/
│   ├── proration/
│   ├── invoices/
│   ├── payments/
│   ├── dunning/
│   ├── ledger/
│   ├── reconciliation/
│   ├── security/
│   ├── capacity-planning/
│   ├── aws-well-architected/
│   ├── runbooks/
│   └── decisions/
├── backend/
│   ├── pom.xml
│   ├── billing-domain/
│   ├── subscription-management/
│   ├── pricing-catalog/
│   ├── billing-engine/
│   ├── invoice-management/
│   ├── payment-orchestration/
│   ├── dunning/
│   ├── usage-metering/
│   ├── ledger/
│   ├── reconciliation/
│   ├── entitlement-management/
│   ├── notification-worker/
│   └── integration-tests/
├── frontend/
│   ├── app/
│   ├── components/
│   ├── features/
│   ├── hooks/
│   ├── lib/
│   ├── styles/
│   └── tests/
├── infrastructure/
│   ├── postgres/
│   ├── redis/
│   ├── kafka/
│   ├── object-storage/
│   ├── monitoring/
│   ├── docker/
│   ├── helm/
│   └── terraform/
├── payment-simulator/
├── demo-data/
├── benchmarks/
├── end-to-end-tests/
└── .github/workflows/
```

Adapt module boundaries to the actual implementation.

Do not create empty modules or unnecessary deployables.

---

# PART AD — IMPLEMENTATION WORKFLOW

## 62. EXECUTE PHASE BY PHASE

### Phase 1 — Requirements and Architecture

Define billing rules, financial invariants, supported countries/currencies, provider capabilities, scale assumptions, and SLOs.

### Phase 2 — Java 21 and Spring Boot 4.x Foundation

Implement project structure, dependency management, Spring Security, PostgreSQL, migrations, and local infrastructure.

### Phase 3 — Next.js Design System

Implement responsive SaaS financial UI components, authentication flow, navigation, tables, and form patterns.

### Phase 4 — Product and Price Catalog

Implement products, immutable price versions, currencies, and plan APIs.

### Phase 5 — Subscription Management

Implement lifecycle, trials, activation, plan changes, pause, resume, and cancellation.

### Phase 6 — Billing Calendar Engine

Implement billing anchors, monthly/quarterly/annual recurrence, time zones, leap years, and deterministic tests.

### Phase 7 — Durable Scheduler

Implement jobs, leases, unique cycle execution, retries, crash recovery, and multi-instance tests.

### Phase 8 — Invoice Management

Implement draft/finalized invoices, numbering, line items, tax, discounts, and secure PDFs.

### Phase 9 — Proration

Implement upgrade/downgrade adjustments and price-change previews.

### Phase 10 — Payment Simulator and Provider Adapter

Implement payment attempts, idempotency, ambiguous outcomes, webhooks, and status reconciliation.

### Phase 11 — Retry and Dunning

Implement bounded collection retries, customer reminders, grace periods, and recovery.

### Phase 12 — Refunds and Credits

Implement refunds, partial refunds, credit notes, customer balances, and corrections.

### Phase 13 — Usage Billing

Implement usage ingestion, deduplication, aggregation, late events, and usage invoices.

### Phase 14 — Ledger and Reconciliation

Implement auditable accounting movements, financial constraints, matching, discrepancies, and repair workflows.

### Phase 15 — Entitlements and Notifications

Implement subscription access policies and notification delivery.

### Phase 16 — Customer Portal

Implement pricing, subscription management, billing history, payment methods, and cancellation.

### Phase 17 — Admin/Finance Portal

Implement financial operations, reconciliation, dunning inspection, plans, and reporting.

### Phase 18 — Kafka and Outbox/Inbox

Implement reliable integration events, consumer idempotency, retries, and DLQ.

### Phase 19 — Security and Observability

Implement authorization, tenant isolation, metrics, tracing, audit logs, alerts, and runbooks.

### Phase 20 — Deployment

Implement Docker Compose, Kubernetes/Helm, CI/CD, and AWS reference architecture.

### Phase 21 — Verification

Execute backend unit, integration, concurrency, security, financial-invariant, and Playwright tests.

### Phase 22 — Performance

Run load tests, benchmark billing/scheduler throughput, identify contention, and optimize based on evidence.

### Phase 23 — Final Architecture Review

Evaluate all six AWS Well-Architected pillars, financial correctness, operational risks, reliability, and compliance gaps.

For every phase:

- Create real source files.
- Keep the project buildable.
- Run relevant tests.
- Fix meaningful failures.
- Preserve unrelated work.
- Document architectural decisions.
- Report actual verification.
- Identify incomplete features and risks.

---

# PART AE — REQUIRED DELIVERABLES

## 63. COMPLETE PROJECT OUTPUTS

Deliver:

1. Functional/non-functional requirements
2. HLD architecture diagrams
3. LLD domain and sequence diagrams
4. Java 21 Spring Boot 4.x backend
5. Product and pricing catalog
6. Subscription lifecycle
7. Billing calendar engine
8. Durable scheduler
9. Proration engine
10. Usage-based billing
11. Invoice management
12. Tax calculation boundary
13. PDF invoice generation
14. Payment simulator/provider adapter
15. Payment idempotency
16. Retry and dunning
17. Refund and credit-note support
18. Entitlement management
19. Auditable ledger
20. Reconciliation engine
21. Kafka integration
22. Transactional Outbox/Inbox
23. PostgreSQL migrations
24. Next.js customer billing portal
25. Next.js admin/finance portal
26. Automated unit tests
27. Integration tests
28. Concurrency tests
29. Financial invariant tests
30. Payment failure simulations
31. Frontend Playwright tests
32. Security tests
33. Benchmark scripts
34. Docker Compose
35. Kubernetes/Helm
36. CI/CD
37. Observability dashboards
38. AWS six-pillar review
39. Architecture Decision Records
40. Operational runbooks
41. Final verification report

---

# PART AF — MANDATORY ARCHITECTURE REVIEW

## 64. ANSWER THESE 50 QUESTIONS

1. How would you design a subscription billing platform for millions of customers?
2. How do recurring billing cycles work?
3. How do you model monthly and annual subscriptions?
4. How do you handle billing on January 31?
5. How do leap years affect renewals?
6. Why is a durable scheduler required?
7. How do you prevent duplicate renewal jobs?
8. How do you handle a scheduler crash after invoice creation?
9. What is the difference between a product and a price?
10. Why should price versions be immutable?
11. How do free trials work?
12. How do you prevent trial abuse?
13. How does immediate plan upgrade work?
14. How do you implement proration?
15. How do you handle multiple plan changes in one period?
16. How do you handle subscription cancellation at period end?
17. What is the invoice lifecycle?
18. How do you guarantee unique invoice numbers?
19. Why should finalized invoices be immutable?
20. How do credit notes work?
21. How do you handle tax-inclusive versus tax-exclusive billing?
22. How do recurring payment mandates work?
23. How do you prevent duplicate charges?
24. How do you handle an ambiguous provider timeout?
25. How do payment webhooks work?
26. How do you handle duplicate and out-of-order webhooks?
27. What is dunning?
28. How do you implement safe payment retries?
29. What is the difference between a hard decline and a transient failure?
30. How do you manage grace periods and entitlements?
31. How do you implement usage-based billing?
32. How do you prevent duplicate usage metering?
33. How do you handle late usage data?
34. What is the difference between refunds and credits?
35. How do you prevent excessive concurrent refunds?
36. Why is an auditable ledger necessary?
37. How does double-entry bookkeeping work?
38. How do you reconcile invoices and provider payments?
39. How do you identify orphaned payments?
40. How does Transactional Outbox prevent lost integration events?
41. How do you make Kafka consumers idempotent?
42. When is Saga appropriate in billing systems?
43. How do you design multi-tenant billing isolation?
44. How do you handle multiple currencies safely?
45. How do you design a low-latency billing portal?
46. How do you scale monthly renewal spikes?
47. How do you benchmark financial correctness under concurrency?
48. How do you recover after a regional outage?
49. How do the six AWS Well-Architected pillars apply?
50. What evidence is required before production financial-system adoption?

For every answer provide:

- Architecture reasoning
- Domain model
- Concurrency handling
- Financial invariants
- Failure scenarios
- Recovery
- Scalability
- Security
- Operational trade-offs

---

# DEFINITION OF DONE

## 65. ACCEPTANCE CRITERIA

The implementation is complete only when:

- Java 21 Spring Boot 4.x backend builds.
- Next.js frontend builds.
- PostgreSQL migrations execute.
- Product and plan management works.
- Subscription creation is idempotent.
- Trials work.
- Monthly, quarterly, and annual billing dates are calculated correctly.
- End-of-month and leap-year cases pass tests.
- Durable scheduler prevents duplicate logical billing cycles.
- Invoice creation and finalization work.
- Invoice totals are currency-safe.
- Proration works under documented policies.
- Payment simulator works.
- Provider integration respects verified capabilities.
- Payment retries are idempotent and bounded.
- Ambiguous payment outcomes are reconciled.
- Webhook duplicates are handled.
- Dunning works.
- Refunds and credit notes work.
- Usage metering is duplicate-safe.
- Ledger posting is auditable.
- Reconciliation identifies financial mismatches.
- Entitlement changes follow subscription policy.
- Customer billing portal is functional.
- Admin/finance portal is functional.
- Invoice PDF access is authorized.
- Tenant isolation is verified.
- Docker Compose demonstrates an end-to-end billing cycle.
- Kubernetes deployment manifests are provided.
- Automated tests cover critical concurrency and failures.
- Observability metrics and logs work.
- Benchmark scripts are reproducible.
- Scale targets are measured or explicitly unverified.
- All six AWS Well-Architected pillars are reviewed.
- Known limitations and financial risks are documented.

Do not declare production readiness merely because a subscription can be created and charged once.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **complete enterprise Subscription Billing and Recurring Payments Platform using Java 21, Spring Boot 4.x, and Next.js**.

Prioritize:

1. **Financial correctness:** No duplicate invoices, charges, refunds, or ledger effects under the supported model.
2. **Billing calendar accuracy:** Correct recurrence, time zones, month boundaries, and proration.
3. **Idempotency:** Durable operation identities across retries, workers, APIs, and webhooks.
4. **Payment recovery:** Explicit handling of unknown provider outcomes and reconciliation.
5. **Subscription consistency:** Valid lifecycle, entitlements, and cancellation semantics.
6. **Invoice integrity:** Immutable finalized documents, accurate totals, and auditable adjustments.
7. **Dunning reliability:** Controlled retries, customer communication, and grace periods.
8. **Scalability:** Distributed scheduling, bounded workers, and efficient batch renewal.
9. **Security:** Strong authorization, tenant isolation, and protected financial data.
10. **Maintainability:** DDD, SOLID, Clean/Hexagonal Architecture, and justified patterns.
11. **Frontend UX:** Responsive, accessible, transparent billing and finance workflows.
12. **Observability:** Billing failures, payment uncertainty, reconciliation drift, and SLO monitoring.
13. **Cloud quality:** Apply all six AWS Well-Architected pillars.
14. **Reproducibility:** Complete source, deterministic payment simulator, seed data, tests, Docker Compose, and Kubernetes.

Begin with a concise architecture assessment.

Select the subscription domain model, billing calendar semantics, distributed scheduler, invoice lifecycle, payment collection strategy, ledger/accounting model, and reconciliation approach.

Then implement the project phase by phase with working code and verification.

Use available development tools to create source files, compile, run tests, simulate payment failures, verify concurrency, benchmark, and fix defects.

At completion report:

- Implemented backend features
- Implemented Next.js pages
- Billing-cycle and proration rules
- Financial and idempotency guarantees
- Architecture decisions and trade-offs
- Source structure
- Exact build/run commands
- Tests actually executed
- Benchmark results actually measured
- AWS Well-Architected assessment
- Known limitations
- Remaining financial, compliance, security, and production risks

**DO NOT STOP AFTER HLD OR LLD. DELIVER THE COMPLETE END-TO-END SUBSCRIPTION BILLING PLATFORM INCLUDING RECURRING BILLING, INVOICES, PAYMENT COLLECTION, IDEMPOTENCY, RETRIES, DUNNING, PRORATION, REFUNDS, LEDGER, RECONCILIATION, FUNCTIONAL NEXT.JS APPLICATIONS, AUTOMATED VERIFICATION, AND DEPLOYMENT INFRASTRUCTURE.**
````