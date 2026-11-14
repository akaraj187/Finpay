import java.util.*;

/** Tiny dependency-free JSON reader/writer. */
public final class Json {
    private final String s; private int i;
    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text); p.ws();
        Object v = p.value(); p.ws();
        if (p.i != p.s.length()) throw new IllegalArgumentException("Trailing JSON content");
        return v;
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IllegalArgumentException("JSON object expected");
        return (Map<String, Object>) o;
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("Unexpected end");
        char c = s.charAt(i);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '"') return str();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return num();
    }
    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>(); i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws(); String k = str(); ws();
            if (s.charAt(i++) != ':') throw new IllegalArgumentException("':' expected");
            ws(); m.put(k, value()); ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("',' expected");
        }
    }
    private List<Object> arr() {
        List<Object> l = new ArrayList<>(); i++; ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws(); l.add(value()); ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("',' expected");
        }
    }
    private String str() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("String expected");
        StringBuilder b = new StringBuilder(); i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            } else b.append(c);
        }
    }
    private Object num() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(st, i);
        if (t.isEmpty()) throw new IllegalArgumentException("Invalid JSON");
        if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
        return Long.parseLong(t);
    }

    public static String write(Object o) { StringBuilder b = new StringBuilder(); w(o, b); return b.toString(); }
    private static void w(Object o, StringBuilder b) {
        if (o == null) b.append("null");
        else if (o instanceof String x) q(x, b);
        else if (o instanceof Number || o instanceof Boolean) b.append(o);
        else if (o instanceof Map<?, ?> m) {
            b.append('{'); boolean f = true;
            for (var e : m.entrySet()) { if (!f) b.append(','); f = false; q(String.valueOf(e.getKey()), b); b.append(':'); w(e.getValue(), b); }
            b.append('}');
        } else if (o instanceof Iterable<?> it) {
            b.append('['); boolean f = true;
            for (Object x : it) { if (!f) b.append(','); f = false; w(x, b); }
            b.append(']');
        } else q(o.toString(), b);
    }
    private static void q(String x, StringBuilder b) {
        b.append('"');
        for (char c : x.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        b.append('"');
    }
    /** Ordered map helper: m("a",1,"b",2). */
    public static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int k = 0; k < kv.length; k += 2) r.put((String) kv[k], kv[k + 1]);
        return r;
    }
}
