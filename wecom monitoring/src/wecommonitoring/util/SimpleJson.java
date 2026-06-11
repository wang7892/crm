package wecommonitoring.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SimpleJson {
    private SimpleJson() {
    }

    public static Object parse(String json) {
        if (json == null) {
            return null;
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("Unexpected JSON tail at " + parser.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    public static Map<String, Object> getObject(Map<String, Object> map, String key) {
        if (map == null) {
            return Map.of();
        }
        return asObject(map.get(key));
    }

    public static List<Object> getArray(Map<String, Object> map, String key) {
        if (map == null) {
            return List.of();
        }
        return asArray(map.get(key));
    }

    public static String getString(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        return asString(map.get(key));
    }

    public static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    public static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim()).longValue();
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return MiniJson.quote(s);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(MiniJson.quote(String.valueOf(entry.getKey()))).append(':').append(toJson(entry.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(toJson(item));
            }
            sb.append(']');
            return sb.toString();
        }
        return MiniJson.quote(String.valueOf(value));
    }

    private static final class Parser {
        private final String json;
        private int pos;

        private Parser(String json) {
            this.json = json;
        }

        private boolean end() {
            return pos >= json.length();
        }

        private void skipWhitespace() {
            while (!end()) {
                char ch = json.charAt(pos);
                if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                    pos++;
                    continue;
                }
                break;
            }
        }

        private Object parseValue() {
            skipWhitespace();
            if (end()) {
                throw new IllegalArgumentException("Unexpected JSON end");
            }
            char ch = json.charAt(pos);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> consumeLiteral("true", Boolean.TRUE);
                case 'f' -> consumeLiteral("false", Boolean.FALSE);
                case 'n' -> consumeLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!end() && json.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (!end() && json.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                return map;
            }
        }

        private List<Object> parseArray() {
            pos++;
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!end() && json.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (!end() && json.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                return list;
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!end()) {
                char ch = json.charAt(pos++);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch != '\\') {
                    sb.append(ch);
                    continue;
                }
                if (end()) {
                    throw new IllegalArgumentException("Bad JSON escape at end");
                }
                char escaped = json.charAt(pos++);
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > json.length()) {
                            throw new IllegalArgumentException("Bad unicode escape at " + pos);
                        }
                        String hex = json.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad JSON escape \\" + escaped + " at " + pos);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Object parseNumber() {
            int start = pos;
            if (!end() && json.charAt(pos) == '-') {
                pos++;
            }
            while (!end() && Character.isDigit(json.charAt(pos))) {
                pos++;
            }
            boolean decimal = false;
            if (!end() && json.charAt(pos) == '.') {
                decimal = true;
                pos++;
                while (!end() && Character.isDigit(json.charAt(pos))) {
                    pos++;
                }
            }
            if (!end() && (json.charAt(pos) == 'e' || json.charAt(pos) == 'E')) {
                decimal = true;
                pos++;
                if (!end() && (json.charAt(pos) == '+' || json.charAt(pos) == '-')) {
                    pos++;
                }
                while (!end() && Character.isDigit(json.charAt(pos))) {
                    pos++;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("Expected JSON value at " + pos);
            }
            String raw = json.substring(start, pos);
            try {
                return decimal ? new BigDecimal(raw) : Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                return new BigDecimal(raw);
            }
        }

        private Object consumeLiteral(String literal, Object value) {
            if (!json.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Expected " + literal + " at " + pos);
            }
            pos += literal.length();
            return value;
        }

        private void expect(char expected) {
            if (end() || json.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at " + pos);
            }
            pos++;
        }
    }
}
