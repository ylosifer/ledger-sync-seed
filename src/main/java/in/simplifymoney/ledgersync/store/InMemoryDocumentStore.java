package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * In-memory implementation of DocumentStore mirroring the single-table DynamoDB design.
 *
 * Partition Key: PK = "ACCOUNT#" + accountLast4
 * Sort Key:      SK = "TXN#" + occurredAt.toString() + "#" + id
 * Rollup Item:   PK = "ACCOUNT#" + accountLast4, SK = "TOTALS"
 * Message Index: PK = "MSG#" + messageId, SK = "TXN"
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    // Simulated DynamoDB table items: PK -> (SK -> Item attributes)
    private final Map<String, ConcurrentSkipListMap<String, Map<String, Object>>> table =
            new ConcurrentHashMap<>();

    // Message ID lookup: messageId -> transaction
    private final Map<String, NormalizedTxn> messageIndex = new ConcurrentHashMap<>();

    // Rollup totals per account: accountLast4 -> Category -> total
    private final Map<String, Map<Category, BigDecimal>> accountTotals = new ConcurrentHashMap<>();

    // Metrics tracking
    private long q1Examined = 0;
    private long q1Returned = 0;
    private long q2Examined = 0;
    private long q2Returned = 0;
    private long q3Examined = 0;
    private long q3Returned = 0;

    @Override
    public synchronized void save(NormalizedTxn txn) {
        String pk = "ACCOUNT#" + txn.accountLast4();
        String uniqueSuffix = txn.sourceMessageIds().isEmpty() ? "0" : txn.sourceMessageIds().get(0);
        String sk = "TXN#" + txn.occurredAt().toString() + "#" + uniqueSuffix;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("account_last4", txn.accountLast4());
        item.put("occurred_at", txn.occurredAt().toString());
        item.put("direction", txn.direction().name());
        item.put("amount", txn.amount());
        item.put("category", txn.category().name());
        item.put("merchant", txn.merchant());
        item.put("source_message_ids", txn.sourceMessageIds());

        table.computeIfAbsent(pk, k -> new ConcurrentSkipListMap<>()).put(sk, item);

        // Update running category totals (atomic rollup item SK="TOTALS")
        Map<Category, BigDecimal> totals = accountTotals.computeIfAbsent(txn.accountLast4(),
                k -> new EnumMap<>(Category.class));
        totals.merge(txn.category(), txn.amount(), BigDecimal::add);

        // Index each source message ID
        for (String mid : txn.sourceMessageIds()) {
            messageIndex.put(mid, txn);
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        String pk = "ACCOUNT#" + accountLast4;
        ConcurrentSkipListMap<String, Map<String, Object>> partition = table.get(pk);
        if (partition == null) return List.of();

        // Month sort key range: TXN#YYYY-MM-01T00:00:00 to TXN#YYYY-MM-31T23:59:59~
        String startKey = "TXN#" + month.atDay(1).toString() + "T00:00:00";
        String endKey = "TXN#" + month.atEndOfMonth().toString() + "T23:59:59.999999999~";

        // Query key condition: PK = :pk AND SK BETWEEN :startKey AND :endKey
        // ScanIndexForward = false (newest first)
        List<NormalizedTxn> results = new ArrayList<>();
        var subMap = partition.subMap(startKey, true, endKey, true);

        for (Map<String, Object> item : subMap.descendingMap().values()) {
            results.add(toTxn(item));
        }

        q1Examined += results.size();
        q1Returned += results.size();
        return Collections.unmodifiableList(results);
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        q2Examined += 1; // Point GetItem on PK="ACCOUNT#...", SK="TOTALS"
        Map<Category, BigDecimal> result = new LinkedHashMap<>();
        for (Category c : Category.values()) result.put(c, ZERO);

        Map<Category, BigDecimal> totals = accountTotals.get(accountLast4);
        if (totals != null) {
            totals.forEach((c, v) -> result.put(c, v.setScale(2)));
        }

        q2Returned += 1;
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        q3Examined += 1; // Point GetItem on PK="MSG#" + messageId, SK="TXN"
        NormalizedTxn txn = messageIndex.get(messageId);
        if (txn != null) q3Returned += 1;
        return Optional.ofNullable(txn);
    }

    @SuppressWarnings("unchecked")
    private NormalizedTxn toTxn(Map<String, Object> item) {
        return new NormalizedTxn(
                (String) item.get("account_last4"),
                OffsetDateTime.parse((String) item.get("occurred_at")),
                Direction.valueOf((String) item.get("direction")),
                ((BigDecimal) item.get("amount")).setScale(2),
                Category.valueOf((String) item.get("category")),
                (String) item.get("merchant"),
                (List<String>) item.get("source_message_ids"));
    }

    public long getQ1Examined() { return q1Examined; }
    public long getQ1Returned() { return q1Returned; }
    public long getQ2Examined() { return q2Examined; }
    public long getQ2Returned() { return q2Returned; }
    public long getQ3Examined() { return q3Examined; }
    public long getQ3Returned() { return q3Returned; }
}
