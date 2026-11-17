# Capacity, cloud review and operating risks

Targets are design inputs, not measured capability. One million renewals/day averages 11.57 jobs/s. Ten thousand jobs/s is an 864x burst above that average. At minimum a renewal writes invoice, journal, subscription, job completion, next job and outbox (about 60k writes/s at that burst), excluding indexes/WAL/payment effects. A million PDFs/day at an assumed 50 KB is 50 GB/day, roughly 18.25 TB/year before replication. 100 million usage events/day averages 1157/s. Storage sizes, events per workflow, provider rate limits and retry ratios must be measured. Required provider concurrency is arrival rate × latency subject to contracted limits, independent of billing throughput. The current process uses twelve database connections and claims sixteen jobs per poll; it is NOT designed to achieve 10k/s without measured optimization.

| AWS pillar | Decision | Verification / remaining risk |
|---|---|---|
| Operational excellence | deterministic simulator, repeatable builds, journal/outbox evidence | deployment promotion, full audit trails and incident exercises outstanding |
| Security | OIDC resource server, tenant/subject filters, finance scope | TLS, audience validation, approval workflows, security assessment outstanding |
| Reliability | atomic local financial commits, job leases and business keys | Multi-AZ RDS, PITR and restore drill required; exhausted jobs need manual action |
| Performance efficiency | due-job index, SKIP LOCKED, bounded batch/pool | sustained renewal/usage scale unverified |
| Cost optimization | one backend and database, no mandatory Kafka cluster | provider fees, retention and growth need actual workload estimates |
| Sustainability | due-only queries, bounded work, no duplicate collections | pause polling and idle compute can be improved |

AWS reference: EKS in three AZs, RDS PostgreSQL Multi-AZ as one financial writer, encrypted backups/PITR, private subnets and workload identity, Secrets Manager, ALB TLS, WAF, and CloudWatch/Prometheus. These are reference decisions, not provisioned resources. The local Compose environment now includes S3-compatible invoice artifacts; Kafka/MSK and Redis remain future dependencies. No AWS credentials or deployment destination were supplied and no cloud deployment occurred.

## Runbooks
- Unknown payment: never resend collection. Compare simulator/provider identity, currency, amount and authoritative status; run finance reconciliation; investigate unmatched discrepancies. An OUTAGE remains UNKNOWN.
- Exhausted billing jobs: query jobs where status='READY' and attempts>=8; inspect subscription/invoice/journal facts; correct cause before resetting attempts/lease in an approved audited operation. Never delete an invoice to retry.
- Database outage: stop mutations and workers, recover authoritative primary, validate migrations and invoice/journal relationships before resuming. Rebuild derived projections only after source validation.
- Financial correction: use explicit credit/refund API; do not modify finalized totals or journal rows. Cash refunds and invoice credits are different actions.
- Regional outage: fence old writers, restore to documented RPO, reconcile external charges before resuming. Current deployment does not automate this.

## Not implemented / adoption blockers
This is a runnable foundation, not completion of the master acceptance criteria. Outstanding: full customer/org registration and OIDC browser login, catalog administration and product versions, cross-interval plan changes, immediate downgrade credits, draft/tax invoice model, coupons, jurisdiction-certified invoice templates and legal billing profiles, live-mode provider support, recurring Razorpay mandate/token charging and settlement matching, live-provider retry classification, external email/SMS delivery and per-tenant dunning policy administration, standalone mandate/setup flows, usage rating and late adjustments, settlement matching, customer-credit balances after paid invoices, reporting, Kafka publication/DLQ, Redis, comprehensive metrics/traces/alerts, Helm/Terraform, all 50 detailed failure analyses and architecture answers, full accessibility/security tests and target-scale benchmarks. API lists are bounded to 100 records but cursor pagination is outstanding. Financial immutable tables need database role/retention enforcement beyond triggers. Tests do not establish live provider semantics or compliance.
