package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

    @Test
    void testIngestCorpusA() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        IngestService.Stats stats = ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        assertEquals(522, stats.messagesRead());
        assertEquals(256, stats.transactionsWritten());

        List<NormalizedTxn> txns4821 = store.all().stream()
                .filter(t -> t.accountLast4().equals("4821")).toList();
        List<NormalizedTxn> txns9075 = store.all().stream()
                .filter(t -> t.accountLast4().equals("9075")).toList();
        List<NormalizedTxn> txns3310 = store.all().stream()
                .filter(t -> t.accountLast4().equals("3310")).toList();

        System.out.println("4821 count: " + txns4821.size());
        System.out.println("9075 count: " + txns9075.size());
        System.out.println("3310 count: " + txns3310.size());

        var summary = in.simplifymoney.ledgersync.report.Reports.summary(store.all());
        System.out.println("Summary: " + in.simplifymoney.ledgersync.json.Json.writePretty(summary));
    }
}
