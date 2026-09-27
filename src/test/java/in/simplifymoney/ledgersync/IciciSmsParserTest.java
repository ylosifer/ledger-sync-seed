package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.IciciSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IciciSmsParserTest {

    private final IciciSmsParser parser = new IciciSmsParser();

    @Test
    void parsesV1Format() {
        RawMessage msg = new RawMessage(
                "m-00003-a978bd", "sms", "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-01T10:22:00+05:30"), "dev-1",
                "Dear Customer, Acct XX9075 is debited with INR 22.50 on 01/07/2026 10:22. Info: UPI/VEGETABLE VENDOR. Avl Bal Rs.31,882.25 -ICICI Bank");

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn txn = parsed.get();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("22.50"), txn.amount());
        assertEquals("UPI/VEGETABLE VENDOR", txn.merchant());
        assertEquals(new BigDecimal("31882.25"), txn.statedBalance());
    }

    @Test
    void parsesV2FormatDebit() {
        RawMessage msg = new RawMessage(
                "m-00162-9709eb", "sms", "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T18:41:00+05:30"), "dev-1",
                "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30");

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn txn = parsed.get();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("5.00"), txn.amount());
        assertEquals("UPI/BARBER", txn.merchant());
        assertEquals(new BigDecimal("52841.30"), txn.statedBalance());
    }

    @Test
    void parsesV2FormatCredit() {
        RawMessage msg = new RawMessage(
                "m-00161-5b493e", "sms", "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T16:52:00+05:30"), "dev-1",
                "ICICI Bank Acct XX9075 Cr INR 1250.33 on 23-Jul-2026 16:52; INTEREST CREDIT ref no 424353460512. BalAvl Rs 52,846.30");

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn txn = parsed.get();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals(new BigDecimal("1250.33"), txn.amount());
        assertEquals("INTEREST CREDIT", txn.merchant());
        assertEquals(new BigDecimal("52846.30"), txn.statedBalance());
    }
}
