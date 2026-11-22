package com.gary.photosframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for albums.json: objects, arrays, strings, numbers, true/false/null.
 * Android's org.json is only a stub in plain JUnit tests, so the album store uses this instead and stays
 * testable on the PC. Objects come back as Map, arrays as List, numbers as Double.
 */
final class TinyJson {
    private TinyJson() { }

    // ---- Writing ----

    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    // ---- Reading ----

    static Object parse(String text) {
        Reader r = new Reader(text);
        Object v = r.value();
        r.space();
        if (r.at < text.length()) throw new IllegalArgumentException("extra text at " + r.at);
        return v;
    }

    private static final class Reader {
        final String s;
        int at;
        Reader(String s) { this.s = s; }

        void space() { while (at < s.length() && Character.isWhitespace(s.charAt(at))) at++; }

        char peek() {
            space();
            if (at >= s.length()) throw new IllegalArgumentException("unexpected end");
            return s.charAt(at);
        }

        void expect(char c) {
            if (peek() != c) throw new IllegalArgumentException("expected " + c + " at " + at);
            at++;
        }

        Object value() {
            char c = peek();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
            if (s.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
            if (s.startsWith("null", at)) { at += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            if (peek() == '}') { at++; return m; }
            while (true) {
                String k = string();
                expect(':');
                m.put(k, value());
                char c = peek();
                at++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + (at - 1));
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            expect('[');
            if (peek() == ']') { at++; return l; }
            while (true) {
                l.add(value());
                char c = peek();
                at++;
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (at - 1));
            }
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (at >= s.length()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(at++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(at++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(at, at + 4), 16)); at += 4; break;
                    default: b.append(e);   // \" \\ \/
                }
            }
        }

        Double number() {
            int start = at;
            while (at < s.length() && "+-0123456789.eE".indexOf(s.charAt(at)) >= 0) at++;
            if (start == at) throw new IllegalArgumentException("unexpected " + s.charAt(at) + " at " + at);
            return Double.valueOf(s.substring(start, at));
        }
    }
}
