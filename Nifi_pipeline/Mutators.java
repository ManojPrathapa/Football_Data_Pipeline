
import java.util.*;

/**
 * Syntax-aware mutation operators. Each operator picks a column appropriate to its
 * category (so the CSV stays 122-columns-valid) and corrupts the VALUE inside it in a
 * way targeted at a specific failure mode in the pipeline (Jolt type coercion, SQLite
 * insert, RouteOnAttribute branching, CSV/JSON structural parsing).
 */
public class Mutators {

    public enum MutationType {
        M1_TYPE_COERCION,        // numeric/boolean field -> non-numeric string (breaks Jolt =toInteger/=toDouble/=toBoolean)
        M2_BOUNDARY_VALUE,       // extreme numeric values (overflow, negative, NaN, Infinity)
        M3_NULL_EMPTY,           // required field -> empty / literal "null"
        M4_STRUCTURAL_CORRUPTION,// injects raw ';', '"', '\n' into a string field (CSV/JSON structural stress)
        M5_INJECTION_PAYLOAD,    // SQL-injection-shaped / log4j-shaped payloads (PutDatabaseRecord oracle)
        M6_ENUM_INVALIDATION,    // invalid `type` value (RouteOnAttribute unmatched-path coverage)
        M7_UNICODE_LENGTH_STRESS // unicode/emoji + very long strings (Max String Length limits)
    }

    private static final String[] BOUNDARY_NUMS = {
        String.valueOf(Long.MAX_VALUE), String.valueOf(Long.MIN_VALUE),
        "-1", "0", "999999999999999999999999999999", "-0.0000001",
        "NaN", "Infinity", "-Infinity", "1e309", "0x1F"
    };

    private static final String[] INJECTION_PAYLOADS = {
        "Robert'); DROP TABLE shots;--",
        "' OR '1'='1",
        "'; DELETE FROM passes; --",
        "${jndi:ldap://127.0.0.1/a}",
        "<script>alert(1)</script>",
        "O'Brien",
        "NULL",
        "SELECT * FROM sqlite_master",
        "\u0000nullbyte"
    };

    private static final String[] INVALID_TYPES = {
        "Foul", "Unknown", "PASS", "pass", "Shot ", " Dribble", "", "Pass;Shot",
        "Dribble\nShot", "🏃", "NaN", "12345", "Shoot", "null", "Passs"
    };

    public static String[] mutate(String[] baseRow, Random rnd, List<String> appliedOut) {
        String[] row = baseRow.clone();
        int numMutations = 1 + rnd.nextInt(3); // stack 1-3 mutations per row, still structurally valid
        for (int k = 0; k < numMutations; k++) {
            MutationType m = pickWeighted(rnd);
            apply(row, m, rnd);
            appliedOut.add(m.name());
        }
        return row;
    }

    private static MutationType pickWeighted(Random rnd) {
        // M4 (structural corruption) kept rarer since it deliberately risks unparsable rows.
        MutationType[] pool = {
            MutationType.M1_TYPE_COERCION, MutationType.M1_TYPE_COERCION,
            MutationType.M2_BOUNDARY_VALUE, MutationType.M2_BOUNDARY_VALUE,
            MutationType.M3_NULL_EMPTY,
            MutationType.M5_INJECTION_PAYLOAD, MutationType.M5_INJECTION_PAYLOAD,
            MutationType.M6_ENUM_INVALIDATION, MutationType.M6_ENUM_INVALIDATION,
            MutationType.M7_UNICODE_LENGTH_STRESS,
            MutationType.M4_STRUCTURAL_CORRUPTION
        };
        return pool[rnd.nextInt(pool.length)];
    }

    public static void apply(String[] row, MutationType m, Random rnd) {
        switch (m) {
            case M1_TYPE_COERCION: typeCoercion(row, rnd); break;
            case M2_BOUNDARY_VALUE: boundaryValue(row, rnd); break;
            case M3_NULL_EMPTY: nullEmpty(row, rnd); break;
            case M4_STRUCTURAL_CORRUPTION: structuralCorruption(row, rnd); break;
            case M5_INJECTION_PAYLOAD: injectionPayload(row, rnd); break;
            case M6_ENUM_INVALIDATION: enumInvalidation(row, rnd); break;
            case M7_UNICODE_LENGTH_STRESS: unicodeLengthStress(row, rnd); break;
        }
    }

    private static void typeCoercion(String[] row, Random rnd) {
        List<Integer> targets = new ArrayList<>();
        targets.addAll(CsvSchema.columnsOfType(CsvSchema.FieldType.INTEGER));
        targets.addAll(CsvSchema.columnsOfType(CsvSchema.FieldType.DOUBLE));
        targets.addAll(CsvSchema.columnsOfType(CsvSchema.FieldType.BOOLEAN));
        if (targets.isEmpty()) return;
        int c = targets.get(rnd.nextInt(targets.size()));
        String[] bad = { "abc", "true_ish", "12.34.56", "--5", "1,000", "yes", "1/2", "0b101" };
        row[c] = bad[rnd.nextInt(bad.length)];
    }

    private static void boundaryValue(String[] row, Random rnd) {
        List<Integer> targets = new ArrayList<>();
        targets.addAll(CsvSchema.columnsOfType(CsvSchema.FieldType.INTEGER));
        targets.addAll(CsvSchema.columnsOfType(CsvSchema.FieldType.DOUBLE));
        if (targets.isEmpty()) return;
        int c = targets.get(rnd.nextInt(targets.size()));
        row[c] = BOUNDARY_NUMS[rnd.nextInt(BOUNDARY_NUMS.length)];
    }

    private static void nullEmpty(String[] row, Random rnd) {
        int c = rnd.nextInt(row.length);
        row[c] = rnd.nextBoolean() ? "" : "null";
    }

    private static void structuralCorruption(String[] row, Random rnd) {
        List<Integer> targets = CsvSchema.columnsOfType(CsvSchema.FieldType.STRING);
        if (targets.isEmpty()) return;
        int c = targets.get(rnd.nextInt(targets.size()));
        String[] breakers = { ";INJECTED", "\"unterminated_quote", "line1\nline2", "a;b;c", "\"" };
        row[c] = row[c] + breakers[rnd.nextInt(breakers.length)];
    }

    private static void injectionPayload(String[] row, Random rnd) {
        List<Integer> targets = CsvSchema.columnsOfType(CsvSchema.FieldType.STRING);
        if (targets.isEmpty()) return;
        int c = targets.get(rnd.nextInt(targets.size()));
        row[c] = INJECTION_PAYLOADS[rnd.nextInt(INJECTION_PAYLOADS.length)];
    }

    private static void enumInvalidation(String[] row, Random rnd) {
        int c = CsvSchema.col("type");
        row[c] = INVALID_TYPES[rnd.nextInt(INVALID_TYPES.length)];
    }

    private static void unicodeLengthStress(String[] row, Random rnd) {
        List<Integer> targets = CsvSchema.columnsOfType(CsvSchema.FieldType.STRING);
        if (targets.isEmpty()) return;
        int c = targets.get(rnd.nextInt(targets.size()));
        if (rnd.nextBoolean()) {
            row[c] = "𝕏💥🏆漢字ñ" .repeat(3 + rnd.nextInt(5));
        } else {
            StringBuilder sb = new StringBuilder();
            int len = 2000 + rnd.nextInt(8000);
            for (int i = 0; i < len; i++) sb.append((char) ('a' + rnd.nextInt(26)));
            row[c] = sb.toString();
        }
    }
}
