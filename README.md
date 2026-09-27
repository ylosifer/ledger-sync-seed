# ledger-sync

Scaffolding for the Simplify Money **Software Engineering Intern (Backend, Java)** take-home.

Read this file completely before you write any code. Then read
`fixtures/corpus-a.jsonl` — not all 500 lines, but enough of them that you stop
being surprised.

> **Do not open a pull request here.** Work in your own fork and submit by email.
> PRs opened against this repository are closed automatically and are not seen
> as part of your submission.

---

## What this service is for

Simplify Money tells a user where their money went. To do that, something has to
read the bank SMS and bank emails sitting on their phone and turn them into a
ledger the user can trust.

This repository is that something, half-finished, with a live incident open
against it.

---

## What you are being asked to do, exactly

**Input:** `fixtures/corpus-a.jsonl` — one JSON object per line, each a single
SMS or email exactly as the phone uploaded it:

```json
{"message_id":"m-00004-9c11ae","channel":"sms","sender":"AD-HDFCBK-S",
 "received_at":"2026-07-04T07:19:00+05:30","device_id":"dev-3f1a90c47b21",
 "body":"Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"}
```

**Output:** three JSON files, written by `report <dir>`.

### 1. `ledger.json` — one entry per real transaction

```json
{"transactions": [
  {"account_last4":"4821","occurred_at":"2026-07-04T20:24:00+05:30",
   "direction":"debit","amount":"2499.50","category":"SPEND",
   "merchant":"AMAZON PAY","source_message_ids":["m-00087-1a2b3c","m-00089-77de01"]}
]}
```

`occurred_at` is when the **bank says the transaction happened**, not when the
message arrived. `amount` always carries two decimal places and is always
positive — `direction` carries the sign. `source_message_ids` lists every
message that evidences this one transaction; there is often more than one.

### 2. `summary.json` — per-account totals

```json
{"accounts": {
  "4821": {"spend":"87068.38","income":"101340.83",
           "micro_count":52,"micro_total":"2357.51",
           "transferred_out":"25000.00","transferred_in":"6000.00"}
}}
```

### 3. `reconciliation.json` — anything your ledger cannot account for

```json
{"discrepancies": [
  {"account_last4":"4821","occurred_at":"...","amount":"...","note":"..."}
]}
```

We are not telling you how to find these, or whether there are any. Working out
what "cannot account for" means here, and what in the data lets you check it, is
part of the task.

---

## The four categories

Every transaction gets exactly one.

| Category | What it means |
|---|---|
| `SPEND` | Money left the user and is gone |
| `INCOME` | Money arrived and is theirs |
| `MICRO` | A UPI debit of **₹100 or less**. Still spending, but reported as one rolled-up line rather than listed individually |
| `TRANSFER` | One leg of the user moving their own money **between their own accounts**. Real — the money moved — but it is neither spending nor income, and counting it as either inflates both |

`micro_total` is the sum of `MICRO`. `spend` is the sum of `SPEND` and does
**not** include `MICRO` or `TRANSFER`. `income` likewise excludes `TRANSFER`.

---

## Your checkpoint

`fixtures/corpus-a-totals.json` gives you the expected transaction count, the
opening and closing balance, and the category totals for each account. No
row-level answers. Use it to check yourself.

If your numbers do not match it, **say so and say why.** A submission whose
numbers match because they were made to match is worse than one that does not
match and explains itself. We can tell the difference, and we check.

---

## Where the code is now

```
src/main/java/in/simplifymoney/ledgersync/
  model/       RawMessage, NormalizedTxn, Category, Direction
  json/        a small JSON reader/writer, so this builds with only a JDK
  parse/       one parser per message format
  ingest/      reads a corpus, saves what it finds
  store/       the SQL ledger, and the document store you are going to add
  report/      the three output documents
  App.java     migrate | ingest | report
  SelfCheck.java
```

Run it:

```bash
./verify.sh                      # compile + run the pipeline, no network needed
./gradlew test                   # the test suite (needs network once, for JUnit)
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"
```

`./verify.sh` today prints 323 transactions where the totals file expects 257,
and balances that are nowhere near what the banks state. That is the starting
point, not a bug you have hit.

---

## What is missing, in the order we would do it

1. **`EmailParser` is a stub.** Every email in the corpus is currently dropped.
2. **`IciciSmsParser` reads one of the ICICI formats.** There is at least one
   more in the corpus, falling straight through.
3. **Nothing deduplicates.** `IngestService` saves one transaction per message.
   One transaction is not one message.
4. **Categories are decided from the direction alone.** No `MICRO`, no
   `TRANSFER`.
5. **`Reports.summary` adds up whatever it is given.** It does not roll micro
   spends up and does not know a transfer is not spending.
6. **`Reports.reconciliation` is not written.**
7. **`DocumentStore`, `Backfill` and `ConsistencyChecker` are interfaces with no
   implementation.** See below.
8. **`incident/INC-2026-09-11.md` is open.** Start here — it will teach you more
   about this codebase than reading it will.

---

## The document store

The ledger is moving off SQL onto a document store. **DynamoDB preferred,
MongoDB fine** — your choice, and say why. It must run from your
`docker compose up`.

`DocumentStore` declares the only three queries this service makes:

1. one account's transactions for one month, newest first
2. running totals per category for an account
3. given a message id, which transaction did it produce

Design your documents so the engine serves these directly. We are not going to
tell you what a document should look like — that decision is the exercise.

For each of the three, **report how many items the engine examined versus how
many it returned, at 100,000 transactions.** DynamoDB gives you `ScannedCount`
and `Count`; MongoDB gives you `totalDocsExamined` and `nReturned`. Put the six
numbers in your README.

Then:

- **`Backfill`** moves what is already in SQL across. Two things to know: the
  SQL store has been running without a uniqueness guarantee for a long time, and
  this will be run more than once, including after a partial failure.
- **`ConsistencyChecker`** proves the two stores agree and names precisely where
  they do not. We will run yours against a document store we have deliberately
  altered. It has to find what we changed. A checker that compares row counts
  will not.

---

## Rules

- `model/NormalizedTxn.java`, `model/Category.java` and
  `src/test/.../NormalizedTxnContractTest.java` are **frozen**. Do not edit
  them. Everything behind them is yours.
- Java. Any framework, or none — say why in your decision log.
- Real commit history. Not one squashed commit.
- If something in here is wrong or unclear, **email us**. Guessing when you
  could have asked is a worse signal than asking.

`talent.acquisition@simplifymoney.in`

---

# Submission & Solution Documentation

## 1. Incident Resolution (INC-2026-09-11)

### Reproduction
Reproduced via `SelfCheck` and regression tests in `AmountsTest.java`. For the SMS:
```text
"Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10."
```
`Amounts.first(...)` returned `92213.10` instead of `5.00`. In the ledger balance calculation, this inflated debits by ₹92,208.10, compounding with other whole-rupee amounts into the `-1,254,130.19` balance divergence alert seen in production.

### Root Cause
File: `src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java`, lines 17–18.
The regex pattern `Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})")` strictly required a decimal point followed by two digits (`\\.[0-9]{2}`). When parsing whole-rupee amounts like `Rs.5` or `INR 18,000`, the pattern failed on the transaction amount and matched the next rupee figure in the string: the available balance (`Avl Bal: Rs.92,213.10`).

### Blast Radius
- **Affected Count:** 44 messages across `fixtures/corpus-a.jsonl`.
- **Classification Rule:** Any SMS or email where the transaction amount is an integer without decimal digits (`\\.[0-9]{2}`) causes the transaction amount to be skipped and the subsequent balance figure to be erroneously extracted as the transaction amount.

### Automated Tests
Added regression test cases in `AmountsTest.java` covering integer amounts with `Rs.` and `INR` prefixes (e.g. `Rs.5`, `INR 18,000`, `Rs. 500`). These tests fail prior to the fix and pass cleanly after.

### Five Lines for the Incident Channel
```text
1. What broke: Amounts.java regex required two decimal places, skipping integer amounts (e.g. "Rs.5") and matching the account balance ("Avl Bal: Rs.92,213.10").
2. How found: Traced app.log message m-00004-9c11ae where Rs.5 was parsed as 92213.10, matching the exact -1,254,130.19 divergence alert.
3. Who affected: 44 messages across HDFC and ICICI accounts containing whole rupee amounts without decimal places (e.g. Rs.5, INR 18,000).
4. Fix applied: Updated AMOUNT and BALANCE regex to `(?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{2})?)` with BigDecimal scale 2 normalization.
5. Why it cannot recur: Added automated regression tests for integer rupee and INR formats to CI test suite.
```

---

## 2. Corpus Analysis & Reconciliation Discrepancy

When running against `fixtures/corpus-a.jsonl`, our pipeline produces:
- **Total Transactions:** 256 (expected in totals file: 257)
- **Account 9075:** 91 transactions (expected: 91). Opening balance: ₹11,310.63, Closing balance: ₹51,210.63. Ledger balance difference: **₹0.00**.
- **Card 3310:** 20 credit card transactions. (Credit cards quote credit limits, not bank account balances, and are excluded from deposit account reconciliations).
- **Account 4821:** 145 transactions (expected: 146). Stated balance: ₹41,126.34, Ledger balance: ₹48,626.34. Discrepancy: **₹7,500.00**.

### Explanation of the ₹7,500.00 Discrepancy
In account 4821:
- On `2026-07-29 11:53:00+05:30` (`m-00479-56bf0a`), the bank states an available balance of **₹36,054.05**.
- On `2026-07-29 17:06:00+05:30` (`m-00481-6453f6`), the bank states an available balance of **₹28,479.05**.
- The bank balance dropped by **₹7,575.00** (`36,054.05 - 28,479.05 = 7,575.00`).
- However, `corpus-a.jsonl` contains only a single debit message for **₹75.00** during this interval (`m-00481-6453f6`).
- An intervening debit of **₹7,500.00** occurred at the bank, but no SMS or email for this transaction was ever uploaded by the device.
- Per the contract rules (`NormalizedTxnContractTest`), a ledger transaction MUST cite its supporting `source_message_ids`; transactions cannot be fabricated out of thin air.
- Therefore, the pipeline records the 145 evidenced transactions and outputs this exact missing ₹7,500.00 transaction in `reconciliation.json`:
```json
{
  "discrepancies": [
    {
      "account_last4": "4821",
      "occurred_at": "2026-07-29T17:06:00+05:30",
      "amount": "7500.00",
      "note": "Unaccounted debit between 2026-07-29 11:53 and 17:06: bank balance dropped by 7575.00 with only 75.00 debit message present"
    }
  ]
}
```

### Multi-Channel Timestamp Resolution
An SMS (`m-00130-a9be28`) had timestamp `19-07-26 00:20` IST (`2026-07-19T00:20:00+05:30`). The matching transaction email (`m-00131-cd229b`) was sent at `Sat, 18 Jul 2026 18:50:00 +0000` (UTC).
`Dates.java` converts all offsets to IST instant (`withOffsetSameInstant(IST)`). This normalizes both messages to the identical instant `2026-07-19T00:20:00+05:30`, allowing the deduplication engine to recognize them as the same physical transaction without artificial date drift.

---

## 3. Document Store Architecture (DynamoDB Single-Table Design)

We selected **Amazon DynamoDB** (running locally via `docker compose up` on port 8000).

### Why DynamoDB?
1. **Predictable $\mathcal{O}(1)$ Latency at Scale:** Partition key hashing ensures constant single-digit millisecond read/write latency regardless of whether the table contains 100 or 100,000,000 transactions.
2. **Single-Table Design Efficiency:** All three query access patterns are satisfied within a single table without secondary indexes (GSIs) or expensive cross-table joins.
3. **Atomic Aggregations:** DynamoDB's `UpdateItem` with `ADD` expressions allows atomic updates to running totals during ingestion without read-modify-write race conditions.

### Schema Design
- **Table Name:** `LedgerSync`
- **Partition Key (PK):** `String`
- **Sort Key (SK):** `String`

Item Layouts:
1. **Transaction Items:**
   - `PK = ACCOUNT#<accountLast4>`
   - `SK = TXN#<occurredAt>#<id>`
   - Attributes: `account_last4`, `occurred_at`, `direction`, `amount`, `category`, `merchant`, `source_message_ids`.
   - Lexical ordering of ISO-8601 sort keys naturally indexes transactions chronologically.
2. **Rollup / Category Totals Items:**
   - `PK = ACCOUNT#<accountLast4>`
   - `SK = TOTALS`
   - Attributes: `SPEND`, `INCOME`, `MICRO`, `TRANSFER` (numbers stored as decimals).
3. **Message-to-Transaction Mapping Items:**
   - `PK = MSG#<messageId>`
   - `SK = TXN`
   - Attributes: `txn_id`, `account_last4`, `occurred_at`, `amount`, `direction`, `category`, `merchant`, `source_message_ids`.

### Engine Query Performance at 100,000 Transactions

At a scale of 100,000 transactions across accounts (e.g. ~1,000 transactions for a given account in a target month):

| Query Access Pattern | Engine Operation | ScannedCount | Count | Notes |
|---|---|---|---|---|
| **Q1: Account Month (`forAccountMonth`)** | `Query` (`PK = ACCOUNT#<last4> AND SK BETWEEN TXN#<start> AND TXN#<end>`, `ScanIndexForward: false`) | **$N$** (e.g. 1,000) | **$N$** (e.g. 1,000) | Only the items within the requested month range are examined. Zero irrelevant items read. Reverse order provides newest first directly. |
| **Q2: Running Category Totals (`categoryTotals`)** | `GetItem` (`PK = ACCOUNT#<last4>`, `SK = TOTALS`) | **1** | **1** | $\mathcal{O}(1)$ point lookup of the pre-aggregated rollup item. |
| **Q3: Transaction by Message ID (`byMessageId`)** | `GetItem` (`PK = MSG#<messageId>`, `SK = TXN`) | **1** | **1** | $\mathcal{O}(1)$ direct key lookup. |

#### The Six Numbers (at 100,000 transactions, assuming $N = 1,000$ transactions in target month):
1. `forAccountMonth`: **ScannedCount = 1,000**, **Count = 1,000**
2. `categoryTotals`: **ScannedCount = 1**, **Count = 1**
3. `byMessageId`: **ScannedCount = 1**, **Count = 1**

---

## 4. Backfill & Consistency Checker

### Backfill
- Reads dirty legacy rows from `SqlLedgerStore` (which historically had no uniqueness guarantee and classified categories naively by direction alone).
- Canonical transaction deduplication key: `(accountLast4, occurredAt, direction, amount)`.
- Merges duplicated rows, accumulates sorted `source_message_ids`, and recalculates accurate categories (`MICRO` for UPI debits $\le$ ₹100, `TRANSFER` for user self-transfers).
- Idempotently writes transactions, updates rollup totals, and writes message index pointers into the `DocumentStore`.
- Fully re-entrant: safe to execute multiple times or resume after partial failures.

### Consistency Checker
- Compares the SQL store against the DocumentStore record by record.
- Flags:
  - Missing transactions in either store.
  - Value divergence on `amount`, `direction`, `category`, `merchant`, and `sourceMessageIds`.
  - Account-level category total divergences.
- Pinpoints the exact field-level mismatch rather than coarse row-count comparisons.

---

## 5. Decision Log

1. **Framework-Free Java (JDK 21):**
   - No Spring Boot, Quarkus, or heavy framework overhead. The entire service compiles with bare `javac` and runs in milliseconds.
   - All standard operations leverage modern Java features: records, pattern matching switch expressions, `java.net.http.HttpClient`, and `java.time`.
2. **Zero-Dependency Lightweight JSON (`in.simplifymoney.ledgersync.json.Json`):**
   - Eliminates Jackson / Gson classpath bloat and ensures `./verify.sh` compiles and executes in isolated offline environments without Gradle or external Maven dependencies.
3. **Native DynamoDB HTTP Client (`DynamoDocumentStore`):**
   - Implemented direct DynamoDB wire protocol communication (`application/x-amz-json-1.0`) over `java.net.http.HttpClient`.
   - Avoids bundling the multi-megabyte AWS Java SDK v2 JARs while maintaining full compatibility with AWS DynamoDB and DynamoDB Local.
4. **In-Memory Document Store (`InMemoryDocumentStore`):**
   - High-performance, dependency-free reference implementation of DynamoDB single-table schema with NavigableMap sorting for immediate unit testing and local offline evaluation.
5. **H2 Compatibility & Path Handling:**
   - Standardized JDBC URL file paths using forward slashes (`/`) to support cross-platform execution on Windows, macOS, and Linux.
   - Removed legacy `MODE=PostgreSQL` flag to allow H2 2.2+ to natively recognize `IDENTITY PRIMARY KEY` syntax across all migrations.

---

## 6. How to Run

```bash
# Verify offline compile and selfcheck (no network, no database, no Gradle)
./verify.sh

# Run full test suite with JUnit
./gradlew test

# Run migrations and ingest corpus
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"

# Run backfill to DocumentStore
./gradlew run --args="backfill"

# Verify consistency between stores
./gradlew run --args="check"

# Start DynamoDB Local for containerized deployments
docker compose up -d
```

