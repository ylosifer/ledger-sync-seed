package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackfillAndConsistencyTest {

    private Path tempDb;
    private SqlLedgerStore sqlStore;
    private InMemoryDocumentStore docStore;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("test-ledger", ".db");
        Files.deleteIfExists(tempDb);
        sqlStore = new SqlLedgerStore(tempDb);
        sqlStore.migrate(Path.of("db", "migration"));
        docStore = new InMemoryDocumentStore();
    }

    @AfterEach
    void tearDown() throws Exception {
        sqlStore.close();
        Files.deleteIfExists(tempDb);
        Files.deleteIfExists(Path.of(tempDb.toString() + ".mv.db"));
    }

    @Test
    void testBackfillDeduplicatesAndIsIdempotent() {
        long sqlCount = sqlStore.count();
        assertEquals(15, sqlCount); // V2__seed.sql has 15 rows

        Backfill backfill = new Backfill(sqlStore, docStore);
        Backfill.Result result1 = backfill.run();

        assertEquals(15, result1.read());
        assertEquals(10, result1.written()); // 10 unique transactions
        assertEquals(5, result1.skipped());  // 5 duplicates in seed

        // Second run must be completely idempotent: 0 written, 15 skipped
        Backfill.Result result2 = backfill.run();
        assertEquals(15, result2.read());
        assertEquals(0, result2.written());
        assertEquals(15, result2.skipped());
    }

    @Test
    void testConsistencyCheckerDetectsDivergence() {
        // Prepare clean matching transactions in SQL and DocumentStore
        NormalizedTxn txn = new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-04T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"),
                Category.SPEND, "STORE A", List.of("m-clean-1"));

        sqlStore.save(txn);
        docStore.save(txn);

        // Alter the document store with a modified transaction
        NormalizedTxn alteredTxn = new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-04T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("999.00"), // altered amount
                Category.SPEND, "STORE A", List.of("m-clean-1"));

        InMemoryDocumentStore alteredDocStore = new InMemoryDocumentStore();
        alteredDocStore.save(alteredTxn);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, alteredDocStore);
        List<ConsistencyChecker.Divergence> diffs = checker.check();

        assertFalse(diffs.isEmpty());
        assertTrue(diffs.stream().anyMatch(d -> d.what().contains("amount") || d.what().contains("missing")));
    }
}
