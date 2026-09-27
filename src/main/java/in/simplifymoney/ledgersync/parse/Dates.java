package in.simplifymoney.ledgersync.parse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Bank SMS carry a local date and time and no timezone. The customer, the bank
 * and the branch are all in India, so these are IST.
 */
public final class Dates {

    private Dates() {}

    public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH));

    /** Parse a local date-time written by a bank, as IST. */
    public static OffsetDateTime ist(String dateAndTime) {
        String trimmed = dateAndTime.trim();
        for (DateTimeFormatter f : FORMATS) {
            try {
                return LocalDateTime.parse(trimmed, f).atOffset(IST);
            } catch (DateTimeParseException ignored) {
                // try parsing with offset
                try {
                    return OffsetDateTime.parse(trimmed, f).withOffsetSameInstant(IST);
                } catch (DateTimeParseException ignored2) {
                    // try the next shape
                }
            }
        }
        return null;
    }
}
