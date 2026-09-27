# Track Flow Teardown — Simplify Money

A comprehensive product and engineering analysis of Simplify Money's core **Track** flow, evaluating user experience, technical architecture, edge cases, failure modes, and architectural recommendations.

---

## 1. What is the "Track" Flow?

The **Track** flow is the heartbeat of Simplify Money. It provides automated, zero-effort personal finance tracking by converting unstructured, asynchronous bank communications (SMS and emails) on a user's smartphone into a verified, double-entry grade ledger.

### Core User Journey
1. **Onboarding & Permission Grant:**
   - User grants Read SMS and/or Email sync permissions.
   - Initial ingestion parses historical messages (last 30–90 days) to establish starting account balances and identify bank accounts/cards.
2. **Real-Time Transaction Capture:**
   - Device OS triggers broadcast receiver when an SMS arrives or periodic background sync fetches transactional emails.
   - Messages are parsed, deduplicated, categorized, and synced to the user's ledger.
3. **The Track Dashboard:**
   - **Running Balance Cards:** Stated bank balance per account (e.g. HDFC `**4821`, ICICI `**9075`).
   - **Monthly Spend Velocity:** Total spending for the active month, excluding transfers and isolating micro-transactions.
   - **Categorized Feed:** Grouped by `SPEND`, `INCOME`, `MICRO`, and `TRANSFER`.
   - **Reconciliation Alerts:** Subtle notifications when bank-stated balance diverges from the calculated ledger.

---

## 2. Strengths of the Current Design

1. **Zero-Friction Ingestion:**
   - Unlike manual expense trackers (e.g. Spendee) or bank-scraping tools that break frequently, reading device notifications provides instant, passive capture without requiring net-banking passwords.
2. **Noise Reduction via `MICRO` Transactions:**
   - Frequent UPI payments ($\le$ ₹100, such as tea, auto-rickshaw, or snacks) make up ~30-50% of urban Indian banking transactions. Rolling them into a clean aggregate prevents feed clutter and cognitive fatigue.
3. **Neutral Handling of `TRANSFER`:**
   - Recognizing self-transfers between user accounts (e.g. transferring ₹25,000 from ICICI `9075` to HDFC `4821`) prevents artificial inflation of both income and expenses—a notorious flaw in competing finance apps.
4. **Cross-Channel Deduplication:**
   - Capturing the same physical transaction from both SMS and email without duplicating ledger entries ensures financial integrity.

---

## 3. Key Failure Modes & Vulnerabilities

Through our work on **INC-2026-09-11** and `corpus-a` reconciliation, we identified several critical failure modes:

| Failure Mode | Root Cause | Impact | Observed Example |
|---|---|---|---|
| **Greedy Regex / Token Ambiguity** | Inflexible regex requiring fixed decimals (`\.[0-9]{2}`) | Integer rupee amounts skipped; parser erroneously captures available balance | `INC-2026-09-11`: ₹5 water can parsed as ₹92,213.10 |
| **Missing Message Drop (Silent Gap)** | Telco delivery failure, spam filter deletion, or delayed device sync | Ledger balance diverges from actual bank balance without explanation | Account `4821`: Missing ₹7,500 debit between 11:53 and 17:06 on 2026-07-29 |
| **Timezone & Multi-Channel Drift** | SMS emitted in IST without timezone; emails emitted in RFC-1123 UTC (`+0000`) | Naive date parsing without offset alignment creates fake 1-day drift and prevents deduplication | `m-00130-a9be28` (SMS) vs `m-00131-cd229b` (Email) |
| **Credit Card vs Deposit Balance Confusion** | Credit card messages report "Available Credit Limit" rather than account cash balance | Treating credit limit as a deposit balance corrupts net worth calculations | ICICI Card `3310` quoting available credit limits |
| **Recurring E-Mandate Desync** | Standing instructions and e-mandates often use distinct notification formats without merchant prefixes | Debits missed or classified as unknown merchants | HDFC standing instruction debit notifications |

---

## 4. Product & Engineering Recommendations

### 1. Hybrid Client-Side Parser with Self-Healing Fallback
- **Local On-Device Parsing:** Keep sensitive transaction parsing on the device (using WebAssembly or native Kotlin/Swift engines) to preserve user privacy and reduce server ingest costs.
- **Canary Regex Updates:** Decouple regex/parser rules from full app releases via signed dynamic rule bundles fetched over CDN.

### 2. Explainable Reconciliation UI ("Discrepancy Resolver")
- When bank-stated balance drops without an accompanying transaction message (such as the ₹7,500 gap on `4821`):
  - Do **not** fabricate phantom transactions.
  - Present an interactive banner on the Track screen:  
    *"Your HDFC balance dropped by ₹7,500 on July 29, but we didn't receive an SMS for this. Did you make an ATM withdrawal or offline purchase?"*
  - Allow the user to confirm with a single tap, cleanly closing the reconciliation gap.

### 3. Idempotent Event-Driven Backend Architecture
- Ingest pipeline should enforce partition key deduplication `(accountLast4, occurredAt, direction, amount)` at the database layer (DynamoDB condition expressions).
- Maintain pre-aggregated category totals in $\mathcal{O}(1)$ rollup items (`SK = TOTALS`) updated via atomic `ADD` operations, ensuring sub-50ms dashboard render speeds.

### 4. Anomaly Detection Watchdogs
- Deploy an automated sanity check before persisting ledger transactions:
  - If `transaction_amount == stated_balance` within a tolerance of $\pm 10\%$, flag for secondary inspection.
  - If a transaction debit exceeds $3\times$ the account's historical average daily spend, run an auxiliary regex pass to verify whole-rupee vs decimal parsing.

---

## 5. Summary
The Track flow's reliability is binary: users either trust it completely or abandon it after a single balance hallucination. By pairing robust, multi-format regex parsing with explicit balance reconciliation and event-driven deduplication, Simplify Money delivers a trustworthy, enterprise-grade personal finance ledger.
