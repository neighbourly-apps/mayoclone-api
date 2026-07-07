package com.mayoclone.util;

import java.util.List;

/**
 * Minimal RFC-4180-ish CSV writing helper. A field is quoted when it contains a
 * comma, double-quote, CR or LF; embedded double-quotes are doubled. Rows are
 * joined with CRLF.
 */
public final class Csv {

    private Csv() {
    }

    /** Escape a single field, null -> empty string. */
    public static String field(Object value) {
        String s = value == null ? "" : value.toString();
        boolean mustQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!mustQuote) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Join and escape one row; terminates with CRLF. */
    public static String row(List<?> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(field(cells.get(i)));
        }
        return sb.append("\r\n").toString();
    }
}
