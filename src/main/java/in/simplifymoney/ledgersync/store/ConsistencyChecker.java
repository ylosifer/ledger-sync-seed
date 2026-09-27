package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Compares transactions field-by-field, checks for missing and extra records,
 * and validates category totals.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> diffs = new ArrayList<>();
        List<NormalizedTxn> sqlRows = sql.all();

        // 1. Group / deduplicate SQL rows into canonical transactions
        Map<String, NormalizedTxn> sqlTxns = new LinkedHashMap<>();
        Map<String, Map<Category, BigDecimal>> sqlTotals = new LinkedHashMap<>();
        Set<YearMonth> months = new TreeSet<>();
        Set<String> accounts = new TreeSet<>();

        for (NormalizedTxn t : sqlRows) {
            String key = txnKey(t);
            accounts.add(t.accountLast4());
            months.add(YearMonth.from(t.occurredAt()));

            if (!sqlTxns.containsKey(key)) {
                sqlTxns.put(key, t);
                sqlTotals.computeIfAbsent(t.accountLast4(), k -> new EnumMap<>(Category.class))
                        .merge(t.category(), t.amount(), BigDecimal::add);
            } else {
                // Merge message IDs for duplicate SQL rows
                NormalizedTxn existing = sqlTxns.get(key);
                Set<String> mergedMids = new TreeSet<>(existing.sourceMessageIds());
                mergedMids.addAll(t.sourceMessageIds());
                sqlTxns.put(key, new NormalizedTxn(
                        existing.accountLast4(), existing.occurredAt(), existing.direction(),
                        existing.amount(), existing.category(), existing.merchant(),
                        new ArrayList<>(mergedMids)));
            }
        }

        // 2. Verify all SQL transactions exist and match in DocumentStore
        Set<String> checkedDocKeys = new HashSet<>();

        for (Map.Entry<String, NormalizedTxn> entry : sqlTxns.entrySet()) {
            String key = entry.getKey();
            NormalizedTxn expected = entry.getValue();

            // Check lookup by message ID
            NormalizedTxn docTxn = null;
            for (String mid : expected.sourceMessageIds()) {
                Optional<NormalizedTxn> opt = documents.byMessageId(mid);
                if (opt.isPresent()) {
                    docTxn = opt.get();
                    break;
                }
            }

            if (docTxn == null) {
                diffs.add(new Divergence(
                        "transaction missing in documents: " + key,
                        "present (" + expected.amount() + " " + expected.category() + ")",
                        "missing"));
                continue;
            }

            checkedDocKeys.add(txnKey(docTxn));

            // Field-by-field verification
            if (expected.amount().compareTo(docTxn.amount()) != 0) {
                diffs.add(new Divergence(
                        "amount divergence for " + key,
                        expected.amount().toPlainString(),
                        docTxn.amount().toPlainString()));
            }
            if (expected.category() != docTxn.category()) {
                diffs.add(new Divergence(
                        "category divergence for " + key,
                        expected.category().name(),
                        docTxn.category().name()));
            }
            if (expected.direction() != docTxn.direction()) {
                diffs.add(new Divergence(
                        "direction divergence for " + key,
                        expected.direction().name(),
                        docTxn.direction().name()));
            }
            if (!expected.merchant().equals(docTxn.merchant())) {
                diffs.add(new Divergence(
                        "merchant divergence for " + key,
                        expected.merchant(),
                        docTxn.merchant()));
            }
        }

        // 3. Check for extra transactions in DocumentStore via forAccountMonth
        for (String acct : accounts) {
            for (YearMonth ym : months) {
                List<NormalizedTxn> docMonthTxns = documents.forAccountMonth(acct, ym);
                for (NormalizedTxn dt : docMonthTxns) {
                    String dKey = txnKey(dt);
                    if (!sqlTxns.containsKey(dKey)) {
                        diffs.add(new Divergence(
                                "unexpected extra transaction in documents: " + dKey,
                                "missing",
                                "present (" + dt.amount() + " " + dt.category() + ")"));
                    }
                }
            }
        }

        // 4. Verify running category totals
        for (String acct : accounts) {
            Map<Category, BigDecimal> docTotals = documents.categoryTotals(acct);
            Map<Category, BigDecimal> expTotals = sqlTotals.getOrDefault(acct, Collections.emptyMap());

            for (Category c : Category.values()) {
                BigDecimal expVal = expTotals.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                BigDecimal docVal = docTotals.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                if (expVal.compareTo(docVal) != 0) {
                    diffs.add(new Divergence(
                            "category total divergence for account " + acct + " category " + c,
                            expVal.toPlainString(),
                            docVal.toPlainString()));
                }
            }
        }

        return diffs;
    }

    private static String txnKey(NormalizedTxn t) {
        return t.accountLast4() + "#" + t.occurredAt() + "#" + t.direction() + "#" + t.amount().toPlainString();
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
