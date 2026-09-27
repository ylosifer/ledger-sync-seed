package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages, parses them, deduplicates messages that evidence
 * the same underlying transaction, categorizes transactions, and saves them to the ledger store.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        return ingestMessages(readCorpus(corpus));
    }

    public Stats ingestMessages(List<RawMessage> messages) {
        int skipped = 0;
        Map<TxnKey, GroupedTxn> groups = new LinkedHashMap<>();

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn parsed = p.get();
            if (parsed.statedBalance() != null) {
                Reports.recordStatedBalance(parsed.accountLast4(), parsed.occurredAt(),
                        parsed.statedBalance(), parsed.amount(), parsed.direction());
            }

            TxnKey key = new TxnKey(parsed.accountLast4(), parsed.occurredAt(),
                    parsed.direction(), parsed.amount());

            groups.computeIfAbsent(key, GroupedTxn::new).add(parsed, m);
        }

        List<NormalizedTxn> txns = new ArrayList<>();
        for (GroupedTxn g : groups.values()) {
            txns.add(g.toNormalizedTxn());
        }

        txns.sort(Comparator.comparing(NormalizedTxn::occurredAt)
                .thenComparing(NormalizedTxn::accountLast4));

        for (NormalizedTxn t : txns) {
            store.save(t);
        }

        return new Stats(messages.size(), txns.size(), skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public record TxnKey(String accountLast4, OffsetDateTime occurredAt, Direction direction, BigDecimal amount) {}

    private static final class GroupedTxn {
        private final TxnKey key;
        private final List<String> messageIds = new ArrayList<>();
        private String bestMerchant = "";
        private boolean isUpi = false;

        GroupedTxn(TxnKey key) {
            this.key = key;
        }

        void add(ParsedTxn p, RawMessage m) {
            if (!messageIds.contains(p.sourceMessageId())) {
                messageIds.add(p.sourceMessageId());
            }
            if (p.merchant() != null && !p.merchant().isBlank()) {
                if (bestMerchant.isBlank() || p.merchant().length() > bestMerchant.length()) {
                    bestMerchant = p.merchant();
                }
            }
            if (m.body() != null && m.body().toUpperCase().contains("UPI")) {
                isUpi = true;
            }
            if (p.merchant() != null && p.merchant().toUpperCase().contains("UPI")) {
                isUpi = true;
            }
        }

        NormalizedTxn toNormalizedTxn() {
            Collections.sort(messageIds);
            Category category = categorize(key.accountLast4, key.direction, key.amount, bestMerchant, isUpi);
            return new NormalizedTxn(
                    key.accountLast4,
                    key.occurredAt,
                    key.direction,
                    key.amount,
                    category,
                    bestMerchant,
                    messageIds);
        }
    }

    public static Category categorize(String accountLast4, Direction direction,
                                      BigDecimal amount, String merchant, boolean isUpi) {
        String merchUpper = merchant == null ? "" : merchant.toUpperCase();
        // TRANSFER: Movement between user's own accounts (Parag Kapoor)
        if (merchUpper.contains("PARAG KAPOOR")) {
            return Category.TRANSFER;
        }
        if (direction == Direction.DEBIT) {
            // MICRO: UPI debit of ₹100 or less
            if (amount.compareTo(new BigDecimal("100.00")) <= 0 && isUpi) {
                return Category.MICRO;
            }
            return Category.SPEND;
        } else {
            return Category.INCOME;
        }
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
