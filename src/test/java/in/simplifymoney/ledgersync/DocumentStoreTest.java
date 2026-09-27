package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentStoreTest {

    private InMemoryDocumentStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryDocumentStore();
    }

    @Test
    void testForAccountMonthNewestFirst() {
        NormalizedTxn t1 = new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-04T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"),
                Category.SPEND, "STORE A", List.of("m-1"));

        NormalizedTxn t2 = new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-04T18:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("200.00"),
                Category.SPEND, "STORE B", List.of("m-2"));

        NormalizedTxn t3 = new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-08-01T09:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("300.00"),
                Category.SPEND, "STORE C", List.of("m-3"));

        store.save(t1);
        store.save(t2);
        store.save(t3);

        List<NormalizedTxn> july = store.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(2, july.size());
        assertEquals(t2.occurredAt(), july.get(0).occurredAt()); // newest first
        assertEquals(t1.occurredAt(), july.get(1).occurredAt());

        List<NormalizedTxn> aug = store.forAccountMonth("4821", YearMonth.of(2026, 8));
        assertEquals(1, aug.size());
        assertEquals(t3.occurredAt(), aug.get(0).occurredAt());
    }

    @Test
    void testCategoryTotals() {
        store.save(new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-04T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("500.00"),
                Category.SPEND, "AMAZON", List.of("m-1")));

        store.save(new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-05T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("50.00"),
                Category.MICRO, "UPI/TEA", List.of("m-2")));

        store.save(new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-06T10:00:00+05:30"),
                Direction.CREDIT, new BigDecimal("1000.00"),
                Category.INCOME, "SALARY", List.of("m-3")));

        Map<Category, BigDecimal> totals = store.categoryTotals("4821");
        assertEquals(new BigDecimal("500.00"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("50.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("1000.00"), totals.get(Category.INCOME));
        assertEquals(new BigDecimal("0.00"), totals.get(Category.TRANSFER));
    }

    @Test
    void testByMessageId() {
        NormalizedTxn txn = new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-04T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("500.00"),
                Category.SPEND, "AMAZON", List.of("m-100", "m-101"));

        store.save(txn);

        Optional<NormalizedTxn> byM100 = store.byMessageId("m-100");
        assertTrue(byM100.isPresent());
        assertEquals(txn.amount(), byM100.get().amount());

        Optional<NormalizedTxn> byM101 = store.byMessageId("m-101");
        assertTrue(byM101.isPresent());

        Optional<NormalizedTxn> missing = store.byMessageId("m-999");
        assertTrue(missing.isEmpty());
    }
}
