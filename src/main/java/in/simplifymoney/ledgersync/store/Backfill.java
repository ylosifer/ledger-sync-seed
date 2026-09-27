package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Cleans dirty duplicate rows from legacy SQL and ensures idempotent execution.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> sqlRows = source.all();
        long read = sqlRows.size();

        // 1. Deduplicate dirty rows in SQL
        Map<TxnKey, DeduplicatedRow> groups = new LinkedHashMap<>();
        long duplicateRowsSkipped = 0;

        for (NormalizedTxn t : sqlRows) {
            TxnKey key = new TxnKey(t.accountLast4(), t.occurredAt(), t.direction(), t.amount());
            if (groups.containsKey(key)) {
                duplicateRowsSkipped++;
            }
            groups.computeIfAbsent(key, DeduplicatedRow::new).merge(t);
        }

        long written = 0;
        long alreadyPresentSkipped = 0;

        for (DeduplicatedRow row : groups.values()) {
            NormalizedTxn txn = row.toNormalizedTxn();

            // Check if already in target document store
            boolean alreadyPresent = false;
            for (String mid : txn.sourceMessageIds()) {
                Optional<NormalizedTxn> existing = target.byMessageId(mid);
                if (existing.isPresent()) {
                    alreadyPresent = true;
                    break;
                }
            }

            if (alreadyPresent) {
                alreadyPresentSkipped++;
            } else {
                target.save(txn);
                written++;
            }
        }

        long totalSkipped = duplicateRowsSkipped + alreadyPresentSkipped;
        return new Result(read, written, totalSkipped);
    }

    private record TxnKey(String accountLast4, OffsetDateTime occurredAt, Direction direction, BigDecimal amount) {}

    private static final class DeduplicatedRow {
        private final TxnKey key;
        private final List<String> messageIds = new ArrayList<>();
        private String merchant = "";
        private boolean isUpi = false;

        DeduplicatedRow(TxnKey key) {
            this.key = key;
        }

        void merge(NormalizedTxn t) {
            for (String mid : t.sourceMessageIds()) {
                if (!messageIds.contains(mid)) {
                    messageIds.add(mid);
                }
            }
            if (t.merchant() != null && !t.merchant().isBlank()) {
                if (merchant.isBlank() || t.merchant().length() > merchant.length()) {
                    merchant = t.merchant();
                }
            }
            if (merchant.toUpperCase().contains("UPI")) {
                isUpi = true;
            }
        }

        NormalizedTxn toNormalizedTxn() {
            Collections.sort(messageIds);
            Category cat = IngestService.categorize(key.accountLast4, key.direction, key.amount, merchant, isUpi);
            return new NormalizedTxn(
                    key.accountLast4,
                    key.occurredAt,
                    key.direction,
                    key.amount,
                    cat,
                    merchant,
                    messageIds);
        }
    }

    public record Result(long read, long written, long skipped) {}
}
