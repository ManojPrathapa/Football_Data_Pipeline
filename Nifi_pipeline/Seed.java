
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** One corpus entry: a CSV row plus its fuzzing metadata (Zest-style energy scheduling). */
public class Seed {
    private static final AtomicLong COUNTER = new AtomicLong(0);

    public final long id;
    public String[] row;
    public String eventType;
    public int timesChosen = 0;
    public int novelHits = 0;          // how many times a mutation of this seed found new coverage
    public boolean fromRealData;
    public List<String> lineage = new ArrayList<>(); // mutation history for this seed

    public Seed(String[] row, String eventType, boolean fromRealData) {
        this.id = COUNTER.incrementAndGet();
        this.row = row;
        this.eventType = eventType;
        this.fromRealData = fromRealData;
    }

    public Seed copy() {
        Seed s = new Seed(row.clone(), eventType, false);
        s.lineage = new ArrayList<>(lineage);
        return s;
    }

    /** Energy = selection weight. Seeds that keep finding new coverage get picked more (favored). */
    public double energy() {
        double base = 1.0 + (novelHits * 3.0);
        double fatigue = 1.0 / (1.0 + (timesChosen * 0.15));
        return base * fatigue;
    }

    public String csvLine() {
        return String.join(";", row);
    }
}
