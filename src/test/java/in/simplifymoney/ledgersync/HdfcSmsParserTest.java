package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.HdfcSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HdfcSmsParserTest {

    private final HdfcSmsParser parser = new HdfcSmsParser();

    @Test
    void parsesV1Format() {
        RawMessage msg = new RawMessage(
                "m-1", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-04T07:19:00+05:30"), "dev-1",
                "Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161");

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn txn = parsed.get();
        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("5.00"), txn.amount());
        assertEquals("UPI/WATER CAN", txn.merchant());
        assertEquals(new BigDecimal("92213.10"), txn.statedBalance());
    }

    @Test
    void parsesV2Format() {
        RawMessage msg = new RawMessage(
                "m-2", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-08-14T17:10:00+05:30"), "dev-1",
                "Sent INR1,111.11\nTo: NETFLIX\nOn: 14 Aug 26 17:08\nA/c: XX4821\nAvailable Balance: INR 41047.33\n-HDFC Bank");

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn txn = parsed.get();
        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("1111.11"), txn.amount());
        assertEquals("NETFLIX", txn.merchant());
        assertEquals(new BigDecimal("41047.33"), txn.statedBalance());
    }

    @Test
    void parsesCardFormat() {
        RawMessage msg = new RawMessage(
                "m-3", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-03T11:51:00+05:30"), "dev-1",
                "Rs 1,249.99 spent on HDFC Bank Card x3310 at BLINKIT on 03-07-26 11:51. Avl Limit: Rs.196,250.03.");

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn txn = parsed.get();
        assertEquals("3310", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("1249.99"), txn.amount());
        assertEquals("BLINKIT", txn.merchant());
    }

    @Test
    void parsesEmandateFormat() {
        RawMessage msg = new RawMessage(
                "m-4", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-22T06:15:00+05:30"), "dev-1",
                "E-mandate! Rs.649.00 will be deducted from your HDFC Bank A/c XX4821 on 22-07-26 at 06:15 for NETFLIX ENTERTAINMENT. Avl Bal: Rs.46,868.04");

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn txn = parsed.get();
        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("649.00"), txn.amount());
        assertEquals("NETFLIX ENTERTAINMENT", txn.merchant());
        assertEquals(new BigDecimal("46868.04"), txn.statedBalance());
    }
}
