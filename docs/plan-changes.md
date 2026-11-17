# Plan changes and proration

Implemented phase: same-currency, same-interval plan/seat changes on ACTIVE subscriptions. A five-minute persisted quote freezes minor-unit amounts, the effective instant, subscription revision, target price version and quantity. Plan economics and quotes are database protected against mutation; price availability/name remain editable. The UI shows old credit, new charge, exact immediate adjustment, new recurring total, timing and quote expiry before confirmation.

Policy: NOW permits a nondecreasing recurring total, with a paid current-period invoice and no open invoices. Separate HALF_EVEN rounded old/new remaining-second amounts determine the net debit. The quote time, rather than confirmation time, is authoritative for its short validity window. NOW applies plan/quantity immediately and preserves the calendar anchor. New access is granted immediately under this baseline's policy; a verified decline changes the subscription to PAST_DUE under the existing strict entitlement policy. No successful payment is implied by plan-change confirmation. No pro-rata cash refund occurs.

RENEWAL schedules plan/quantity at the next period boundary without changing current access or generating a current-period adjustment. The next renewal transaction consumes the stored price snapshot, writes its invoice and marks the change applied atomically. Only one scheduled change is permitted. Immediate or period-end cancellation marks a scheduled change CANCELLED. Trial, paused, incomplete and past-due accounts must resolve lifecycle/payment issues before making these changes. Different currency/interval and negative immediate adjustments return 409. Discounts, taxes, and usage are not silently included: this phase supports only the existing fixed-price zero-tax model.

Each positive immediate adjustment has a separate invoice (kind PRORATION, null recurring cycle), two immutable signed lines, one balanced receivable/deferred-revenue journal and an outbox event. Recurring invoice identity remains (subscription, cycle). A fully paid prior invoice is never changed. Multiple immediate upgrades are allowed after paying each outstanding adjustment; each subsequent credit uses the latest contracted recurring total. A quote cannot survive a renewal, pause, resume, cancellation or another applied change because subscription revision changes.

## API contract

All requests require authenticated ownership of the subscription or invoice (tenant_id and customer subject). Local demo identity is demo. Validation errors return Problem Details; ownership failures return 404. Responses use minor-unit integers and ISO timestamps.

| Endpoint | Request | Success | Conflict |
|---|---|---|---|
| POST /api/v1/billing/preview | subscription UUID, plan price ID, quantity 1..10000, mode NOW or RENEWAL | 200 quote including id, old_credit, new_charge, net, recurring_total, currency, effective_at, expires_at | 409 ineligible state, currency/interval mismatch, pending change, overdue renewal, unpaid period, immediate downgrade |
| POST /api/v1/subscriptions/{id}/upgrade | quote UUID; Idempotency-Key required | 200 durable change result with optional invoice UUID | 409 stale/expired quote, outstanding invoice, key/payload conflict |
| POST /api/v1/subscriptions/{id}/downgrade | same confirmation contract | 200 change result | same checks; mode is determined by the persisted quote, not endpoint spelling |
| GET /api/v1/subscriptions/{id}/changes | none | 200 history, latest 100 | none |
| GET /api/v1/invoices/{id}/lines | none | 200 immutable signed line items | none |

Preview writes no financial effect and does not require an idempotency key; duplicate previews are independent quotes. Confirmation replays the same change row for the same key/payload even after later lifecycle changes. A second key cannot consume the same quote or create a duplicate adjustment. Subscription row locks serialize confirmation against renewal/cancellation. Outbox events are persisted but remain unpublished until the separate event delivery phase is implemented.

## Scheduler correction

Jobs now carry expected_cycle. V4 backfills pending legacy jobs from the authoritative subscription. The worker locks job and subscription, rejects future-due work, and completes stale-cycle jobs without advancing state. This prevents distinct job IDs for one cycle from accidentally generating the following cycle's invoice. Lease claims alone do not provide this guarantee.

## Residual work

Draft/finalize invoice editing, coupons, taxation, cross-interval/currency changes, credits for immediate downgrades, scheduling changes during trials, replacing/revoking a scheduled plan change independently of subscription cancellation, explicit approval workflows, quote retention and cursor pagination are future work. These constraints are deliberate scope limits, not claims of full master-prompt completion.
