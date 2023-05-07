package blaze.fhir.spec.type.system;

import com.google.common.hash.PrimitiveSink;

import java.time.DateTimeException;
import java.time.Year;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalUnit;

import static java.time.temporal.ChronoField.YEAR;

@SuppressWarnings("UnstableApiUsage")
public final class DateTimeYear implements DateTime, Comparable<DateTimeYear> {

    private final int year;

    private DateTimeYear(int year) {
        this.year = year;
    }

    public static DateTimeYear of(int year) {
        YEAR_RANGE.checkValidValue(year, YEAR);
        return new DateTimeYear(year);
    }

    static DateTimeYear parse(String text) {
        try {
            return of(Integer.parseInt(text.substring(0, 4)));
        } catch (NumberFormatException e) {
            throw new DateTimeException("Invalid date component: " + e.getMessage());
        }
    }

    private static DateTimeYear fromYear(Year year) {
        return of(year.getValue());
    }

    @Override
    public void hashInto(PrimitiveSink sink) {
        sink.putByte((byte) 6);
        sink.putInt(year);
    }

    public DateYear toDate() {
        return DateYear.of(year);
    }

    public DateTimeDate atStartOfYear() {
        return new DateTimeDate(year, 1, 1);
    }

    public DateTimeDate atEndOfYear() {
        return new DateTimeDate(year, 12, 31);
    }

    @Override
    public boolean isSupported(TemporalUnit unit) {
        return Year.of(year).isSupported(unit);
    }

    @Override
    public Temporal with(TemporalField field, long newValue) {
        return fromYear(Year.of(year).with(field, newValue));
    }

    @Override
    public Temporal plus(long amountToAdd, TemporalUnit unit) {
        try {
            return fromYear(Year.of(year).plus(amountToAdd, unit));
        } catch (NumberFormatException e) {
            throw new DateTimeException("Invalid date component: " + e.getMessage());
        }
    }

    /**
     * Returns a copy of this {@code DateTimeYear} with the specified number of years added.
     * <p>
     * This instance is immutable and unaffected by this method call.
     *
     * @param yearsToAdd the years to add, may be negative
     * @return a {@code DateTimeYear} based on this year with the years added, not null
     * @throws DateTimeException if the result exceeds the supported range
     */
    public DateTimeYear plusYears(int yearsToAdd) {
        if (yearsToAdd == 0) {
            return this;
        }
        return of(year + yearsToAdd);
    }

    @Override
    public long until(Temporal endExclusive, TemporalUnit unit) {
        return Year.of(year).until(endExclusive, unit);
    }

    @Override
    public boolean isSupported(TemporalField field) {
        return Year.of(year).isSupported(field);
    }

    @Override
    public long getLong(TemporalField field) {
        return Year.of(year).getLong(field);
    }

    @Override
    public int compareTo(DateTimeYear other) {
        return year - other.year;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof DateTimeYear) {
            return year == ((DateTimeYear) obj).year;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return year;
    }

    @Override
    public String toString() {
        return Integer.toString(year);
    }
}
