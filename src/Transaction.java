import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class Transaction implements java.io.Serializable {

    private static final long serialVersionUID = 1L;
    /** Used for formatting an Instant to a timestamp with a certain pattern. */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd MMM. YYYY - HH:mm:ss")
                                                        .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault());

    private double amount;
    private String timestamp;

    public Transaction(double amount){
        this.amount = amount;
        this.timestamp = FORMATTER.format(Instant.now());
    }

    //Used only by Storage for JSON write
    public Transaction(double amount, String timestamp){
        this.amount = amount;
        this.timestamp = timestamp;
    }

    public double getAmount() {
        return amount;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String toString()
    {
        return String.format("{ \"amount\": \"%f\", \"timestamp\":  \"%s\" }", amount, timestamp);
    }
}
