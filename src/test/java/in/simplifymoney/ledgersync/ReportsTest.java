package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportsTest {

    private InMemoryLedgerStore store;
    private List<NormalizedTxn> ledger;

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));
        ledger = store.all();
    }

    @Test
    void testSummaryReportAccounts() {
        Map<String, Object> summary = Reports.summary(ledger);
        assertTrue(summary.containsKey("accounts"));
        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) summary.get("accounts");

        assertTrue(accounts.containsKey("4821"));
        assertTrue(accounts.containsKey("9075"));
        assertTrue(accounts.containsKey("3310"));

        @SuppressWarnings("unchecked")
        Map<String, Object> acct9075 = (Map<String, Object>) accounts.get("9075");
        assertEquals("39058.11", acct9075.get("spend"));
        assertEquals("41450.33", acct9075.get("income"));
        assertEquals(45, acct9075.get("micro_count"));
        assertEquals("2086.34", acct9075.get("micro_total"));
        assertEquals("6000.00", acct9075.get("transferred_out"));
        assertEquals("25000.00", acct9075.get("transferred_in"));

        @SuppressWarnings("unchecked")
        Map<String, Object> acct4821 = (Map<String, Object>) accounts.get("4821");
        assertEquals("79568.38", acct4821.get("spend")); // 87068.38 minus 7500 unrecorded spend
        assertEquals("101340.83", acct4821.get("income"));
        assertEquals(52, acct4821.get("micro_count"));
        assertEquals("2357.51", acct4821.get("micro_total"));
        assertEquals("25000.00", acct4821.get("transferred_out"));
        assertEquals("6000.00", acct4821.get("transferred_in"));
    }

    @Test
    void testReconciliationReport() {
        Map<String, Object> recon = Reports.reconciliation(ledger);
        assertNotNull(recon.get("discrepancies"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disc = (List<Map<String, Object>>) recon.get("discrepancies");
        assertEquals(1, disc.size());
        assertEquals("4821", disc.get(0).get("account_last4"));
        assertEquals("7500.00", disc.get(0).get("amount"));
    }
}
