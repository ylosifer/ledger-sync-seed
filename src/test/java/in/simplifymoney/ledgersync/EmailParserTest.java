package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.EmailParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmailParserTest {

    private final EmailParser parser = new EmailParser();

    @Test
    void parsesHdfcEmailCredit() {
        RawMessage msg = new RawMessage(
                "m-00002-69e4cd", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), "dev-1",
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 4821 has been credited with INR 45,000.\n"
                        + "Merchant / Remarks: SALARY CREDIT\n"
                        + "Transaction reference: 1597155421\n\n"
                        + "This is a system generated email.");

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn txn = parsed.get();
        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals(new BigDecimal("45000.00"), txn.amount());
        assertEquals("SALARY CREDIT", txn.merchant());
        assertEquals(OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), txn.occurredAt());
    }

    @Test
    void parsesIciciEmailDebit() {
        RawMessage msg = new RawMessage(
                "m-00039-27b389", "email", "alerts@icicibank.com",
                OffsetDateTime.parse("2026-07-06T11:25:00+05:30"), "dev-1",
                "Date: Mon, 06 Jul 2026 11:25:00 +0530\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 9075 has been debited with Rs.129.67.\n"
                        + "Merchant / Remarks: RELIANCE SMART\n"
                        + "Transaction reference: 6576810104\n\n"
                        + "This is a system generated email.");

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn txn = parsed.get();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("129.67"), txn.amount());
        assertEquals("RELIANCE SMART", txn.merchant());
        assertEquals(OffsetDateTime.parse("2026-07-06T11:25:00+05:30"), txn.occurredAt());
    }
}
