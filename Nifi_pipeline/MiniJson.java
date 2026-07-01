
import java.util.*;

/**
 * Tiny dependency-free JSON parser. Returns:
 *   objects -> LinkedHashMap<String,Object>
 *   arrays  -> ArrayList<Object>
 *   strings -> String
 *   numbers -> Double
 *   true/false -> Boolean
 *   null -> null
 *
 * Written so the whole fuzzer project compiles with plain `javac`, no Jackson/Gson needed.
 */
public class MiniJson {

    private final String s;
    private int i = 0;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        MiniJson p = new MiniJson(json);
        p.skipWs();
        Object v = p.parseValue();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object o = parse(json);
        if (o instanceof Map) return (Map<String, Object>) o;
        return new LinkedHashMap<>();
    }

    private Object parseValue() {
        skipWs();
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        if (c == '{') return parseObjectInternal();
        if (c == '[') return parseArrayInternal();
        if (c == '"') return parseString();
        if (c == 't' || c == 'f') return parseBool();
        if (c == 'n') { i += 4; return null; }
        return parseNumber();
    }

    private Map<String, Object> parseObjectInternal() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++; // {
        skipWs();
        if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
        while (i < s.length()) {
            skipWs();
            String key = parseString();
            skipWs();
            i++; // :
            Object val = parseValue();
            map.put(key, val);
            skipWs();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == '}') { i++; break; }
            break;
        }
        return map;
    }

    private List<Object> parseArrayInternal() {
        List<Object> list = new ArrayList<>();
        i++; // [
        skipWs();
        if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
        while (i < s.length()) {
            Object val = parseValue();
            list.add(val);
            skipWs();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == ']') { i++; break; }
            break;
        }
        return list;
    }

    private String parseString() {
        StringBuilder sb = new StringBuilder();
        i++; // opening quote
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            String hex = s.substring(i + 2, i + 6);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                        break;
                    default: sb.append(n);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        i++; // closing quote
        return sb.toString();
    }

    private Boolean parseBool() {
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        i += 5; return Boolean.FALSE;
    }

    private Double parseNumber() {
        int start = i;
        while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        String num = s.substring(start, i);
        if (num.isEmpty()) { i++; return 0.0; }
        try { return Double.parseDouble(num); } catch (Exception e) { return 0.0; }
    }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    // ---- convenience accessors ----
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : new ArrayList<>();
    }

    public static String asString(Object o, String def) {
        if (o == null) return def;
        if (o instanceof String) return (String) o;
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(o);
    }

    public static long asLong(Object o, long def) {
        if (o instanceof Double) return ((Double) o).longValue();
        if (o instanceof String) { try { return Long.parseLong((String) o); } catch (Exception e) { return def; } }
        return def;
    }
}
