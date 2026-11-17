# Invoice PDFs and private object storage

Customer and finance portals now download invoice PDFs through authenticated backend routes. PDFs include the original invoice number/identity, billing account subject, issue time, service period, immutable price references, signed line amounts, finalized total, document policy and page numbers. Recurring and proration invoices use the same renderer. Amounts use integer minor units and BigDecimal formatting; currency codes are printed explicitly to avoid unsupported currency glyphs.

## Document semantics

A PDF is the **original finalized charge document**, not a payment receipt or current account-balance statement. Payments, applied credits and cash refunds remain separately visible in the portal. Paying an invoice does not regenerate or mutate its published PDF. The legal customer profile, legal issuer model and jurisdiction-specific taxation are not yet implemented; the document explicitly identifies its sandbox issuer, absent tax/billing profile and zero-tax demo model. Do not use this template as a certified tax invoice.

V12 adds `invoices.issued_at` as an immutable financial fact. New invoices record it at issuance. Existing invoices recover only available InvoiceFinalized event timestamps; missing legacy evidence stays unknown rather than being invented. The first PDF snapshot copies immutable invoice/line facts into `invoice_documents`; later outbox cleanup cannot remove the issue date. Legacy issue dates unavailable at migration are labeled accordingly.

Each invoice has one persisted template-v1 snapshot and one published artifact manifest. The manifest holds its object key, SHA-256 and byte count. Once READY, the manifest cannot be updated or deleted. Snapshot fields are protected before publication too. Invoice ownership, kind, issue time and finalized financial identity are now protected by the database. PDF generation, storage upload, download or failure does not modify invoice amounts, payment state or ledger entries.

## Bounded generation and recovery

Generation is lazy on first authorized download. The snapshot commits before rendering or remote storage I/O. A PostgreSQL conditional claim grants a 30-second lease with a unique fencing token, bounded to eight attempts. Four render slots per process provide backpressure. A failed generation/upload releases its claim with a five-second delay; a crashed claimant can be replaced after lease expiry. A stale worker cannot publish after another claimant changes the token. Concurrent requests can receive 503 while the winning request prepares the file; retrying a GET cannot create financial effects.

Objects use a server-generated key: `invoices/<invoice-uuid>/v1/<sha256>.pdf`. No user-supplied path, tenant label, filename or storage URL is accepted. Content-addressed uploads prevent workers from overwriting each other's candidate objects. Only the winning manifest becomes authoritative. An abandoned worker may leave an unreferenced object; retention/garbage collection needs a future manifest-aware policy, not blind prefix deletion.

Every download authorizes tenant and subject (or finance scope plus tenant) **before** accessing cached bytes. Storage retrieval is capped at 2 MiB and checked against the manifest hash/size before delivery. Missing/corrupt objects return 503, never a silently regenerated historical document. A successful preparation creates an immutable `invoice_document_access` row. This records preparation for delivery, not proof the browser received it. Responses include application/pdf, a generated attachment filename, private/no-store, nosniff and X-Document-SHA256. The Next.js forwarding route streams binary bytes; it no longer decodes PDF bodies as text. PDF forwarding allows a 30-second request budget; ordinary API requests retain 15 seconds.

Renderer limits: 1..1000 invoice lines, 256 KiB serialized snapshot, descriptions up to 2000 characters, account subject up to 2048 characters, at most 64 pages and at most 2 MiB output. Long descriptions wrap and paginate; short rows remain together. The current Standard-14-font template escapes non-ASCII identifier code points as `\u{...}` so identifiers are preserved without blank glyphs. Full international legal-name typography, PDF/UA tagging and language shaping remain future work.

## Storage configuration

Running `docker compose up --build` now starts PostgreSQL, backend, frontend and a pinned LocalStack S3 emulator. Its initialization script creates `billing-invoices`; the backend waits for the bucket health check. LocalStack credentials (`test`/`test`) and its HTTP endpoint are local-development values only. No actual AWS bucket/account is created. Its persisted volume is not evidence of a tested cloud backup/restore policy.

Outside Compose, the default storage is a private local directory at `./var/invoice-documents`. The Docker image also creates a writable directory owned by its unprivileged application UID. Local artifact writes use a temporary file and atomic rename. Configure a persistent volume when using this mode in containers.

To use an actual private S3-compatible bucket:

```text
BILLING_DOCUMENTS_STORAGE=s3
BILLING_DOCUMENTS_S3_BUCKET=<existing-private-bucket>
BILLING_DOCUMENTS_S3_REGION=<bucket-region>
BILLING_DOCUMENTS_S3_ENDPOINT=<optional-trusted-S3-compatible-endpoint>
BILLING_DOCUMENTS_S3_ENCRYPTION=true
```

Credentials use the AWS SDK default provider chain, including workload identity; no storage secret is stored in PostgreSQL. For AWS, provision least-privilege GetObject/PutObject access to the invoice prefix, block public access, and use encryption/versioning/backups under your legal retention policy. Bucket creation/policy management is deliberately outside the application. LocalStack Compose sets encryption false; it does not establish production encryption or IAM behavior.

The S3 adapter uses conditional create, one bounded SDK retry, two-second connection/three-second socket limits, four-second per-attempt and ten-second total API budgets. AES256 server-side encryption is requested by default when S3 mode is configured. S3-compatible deployments must support the conditional-write contract. The backend proxies private objects after authorization rather than issuing transferable public/presigned bearer links. AWS documents [endpoint overrides](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html) and [client timeout configuration](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html).

## APIs and portal

| Endpoint | Authorization | Response |
|---|---|---|
| GET /api/v1/invoices/{id}/pdf | Authenticated tenant and customer subject | Original invoice PDF |
| GET /api/v1/admin/invoices | Finance role/scope and tenant | Latest 100 tenant invoices |
| GET /api/v1/admin/invoices/{id}/pdf | Finance role/scope and tenant | Original invoice PDF |

Customer **Invoices** and finance **Invoices** tables expose Download PDF buttons. Requests carry the active Authorization header, and the browser saves a Blob only after a successful PDF response. No credential is added to a URL. Ownership failures return 404, unauthenticated requests 401, finance-role failures 403, inconsistent source lines 409, and generation/storage backpressure or failure 503. Source totals/currencies must match all finalized lines before a snapshot can be created. Cursor pagination, finance filtering, signed receipts and credit-note PDFs are not implemented in this phase.

## Runbooks

- PENDING and lease expired: retry the authorized GET after next_run. Inspect attempts first. Do not alter financial records to make PDF generation work.
- attempts=8: investigate the renderer/storage failure and use an approved audited recovery procedure. No unbounded automatic reset is provided.
- READY but missing/corrupt object: restore the exact original bytes from the object backup/version; validate against stored SHA-256 and size. Do not repoint a manifest to a newly generated document.
- Storage outage: restore credentials/network/bucket availability, then retry retrieval. No financial state changes are necessary.
- Retention: keep authoritative manifests, access evidence and object versions according to legal policy. Unreferenced content-addressed candidates require separate safe garbage collection.

## Verification boundary

The PDFBox 3.0.8 renderer was compiled on Java 21, text/totals extracted, and single-/multi-page PNGs visually inspected using PDFBox rendering (Poppler was unavailable). Tests execute real PostgreSQL migrations/transactions and LocalStack S3 requests, including a full Spring HTTP-to-S3 download. These tests do not establish AWS IAM, KMS, retention, disaster recovery or legal/tax compliance. See verification.md for commands and actual results.
