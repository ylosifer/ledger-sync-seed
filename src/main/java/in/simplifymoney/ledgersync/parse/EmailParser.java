package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails (HDFC & ICICI).
 */
public final class EmailParser implements MessageParser {

    private static final Pattern PATTERN = Pattern.compile(
            "Date:\\s*(?<date>[^\\r\\n]+).*?"
                    + "Your account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with .*?\\n"
                    + "Merchant / Remarks:\\s*(?<merchant>[^\\r\\n]+)",
            Pattern.DOTALL);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher matcher = PATTERN.matcher(m.body());
        if (!matcher.find()) return Optional.empty();

        BigDecimal amount = Amounts.first(m.body());
        OffsetDateTime at = Dates.ist(matcher.group("date"));
        if (amount == null || at == null) return Optional.empty();

        Direction dir = "debited".equalsIgnoreCase(matcher.group("dir"))
                ? Direction.DEBIT : Direction.CREDIT;
        String merchant = matcher.group("merchant").trim();
        String acct = matcher.group("acct");

        return Optional.of(new ParsedTxn(acct, at, dir, amount, merchant, null, m.messageId()));
    }
}
