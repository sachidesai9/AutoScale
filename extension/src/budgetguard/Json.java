package budgetguard;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/** Minimal JSON writer (no external library): maps, collections, arrays, strings, numbers, booleans, null. */
public final class Json {
    private Json() { }

    public static String write(Object o) {
        StringBuilder s = new StringBuilder();
        write(o, s);
        return s.toString();
    }

    @SuppressWarnings("unchecked")
    static void write(Object o, StringBuilder s) {
        if (o == null) s.append("null");
        else if (o instanceof String) str((String) o, s);
        else if (o instanceof Boolean) s.append(o);
        else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) s.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) s.append((long) d);
            else s.append(String.format(Locale.ROOT, "%.6g", d).replaceAll("(\\.\\d*?)0+(e|$)", "$1$2").replaceAll("\\.(e|$)", "$1"));
        } else if (o instanceof Number) s.append(o);
        else if (o instanceof Map) {
            s.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                if (!first) s.append(',');
                first = false;
                str(String.valueOf(e.getKey()), s);
                s.append(':');
                write(e.getValue(), s);
            }
            s.append('}');
        } else if (o instanceof Collection) {
            s.append('[');
            boolean first = true;
            for (Object v : (Collection<Object>) o) {
                if (!first) s.append(',');
                first = false;
                write(v, s);
            }
            s.append(']');
        } else if (o instanceof double[]) {
            double[] a = (double[]) o;
            s.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) s.append(','); write(a[i], s); }
            s.append(']');
        } else if (o instanceof int[]) {
            int[] a = (int[]) o;
            s.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) s.append(','); s.append(a[i]); }
            s.append(']');
        } else if (o instanceof boolean[]) {
            boolean[] a = (boolean[]) o;
            s.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) s.append(','); s.append(a[i] ? 1 : 0); }
            s.append(']');
        } else if (o instanceof Object[]) {
            Object[] a = (Object[]) o;
            s.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) s.append(','); write(a[i], s); }
            s.append(']');
        } else str(String.valueOf(o), s);
    }

    static void str(String v, StringBuilder s) {
        s.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"': s.append("\\\""); break;
                case '\\': s.append("\\\\"); break;
                case '\n': s.append("\\n"); break;
                case '\r': s.append("\\r"); break;
                case '\t': s.append("\\t"); break;
                case '<': s.append("\\u003c"); break;
                default:
                    if (c < 0x20) s.append(String.format("\\u%04x", (int) c));
                    else s.append(c);
            }
        }
        s.append('"');
    }
}
