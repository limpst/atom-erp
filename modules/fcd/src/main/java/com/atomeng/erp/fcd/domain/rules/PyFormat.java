package com.atomeng.erp.fcd.domain.rules;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python {@code str()}/{@code repr()}/{@code format(v, spec)} 이식 — 룰 메시지('{tx.amount:,.0f}원')가
 * 프로토타입과 글자 단위로 같게 나오도록 한다. 지원 스펙: [[fill]align][sign][0][width][,|_][.precision][f|F|%|g|G|e|E|d|s]
 */
public final class PyFormat {

    private PyFormat() {}

    private static final Pattern SPEC = Pattern.compile(
            "^(?:(.)?([<>=^]))?([+\\- ])?(#)?(0)?(\\d+)?([,_])?(?:\\.(\\d+))?([bcdeEfFgGnosxX%])?$");

    // ------------------------------------------------------------------ str · repr

    public static String str(Object v) {
        if (v instanceof String s) {
            return s;
        }
        return repr(v);
    }

    public static String repr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Long l) {
            return l.toString();
        }
        if (v instanceof Double d) {
            return floatRepr(d);
        }
        if (v instanceof String s) {
            return strRepr(s);
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(repr(list.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Set<?> set) {
            if (set.isEmpty()) {
                return "frozenset()";
            }
            StringBuilder sb = new StringBuilder("frozenset({");
            int i = 0;
            for (Object x : set) {
                if (i++ > 0) {
                    sb.append(", ");
                }
                sb.append(repr(x));
            }
            return sb.append("})").toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            int i = 0;
            for (var e : m.entrySet()) {
                if (i++ > 0) {
                    sb.append(", ");
                }
                sb.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof Collection<?> c) {
            return repr(List.copyOf(c));
        }
        return v.toString();
    }

    private static String strRepr(String s) {
        char q = s.contains("'") && !s.contains("\"") ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c == q) {
                        sb.append('\\').append(c);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append(q).toString();
    }

    /** Python float repr: 최단 왕복 자릿수, 1e-4 ≤ |x| < 1e16 고정소수, 그 밖은 지수 표기 */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == 0.0) {
            return (1 / d < 0) ? "-0.0" : "0.0";
        }
        // JDK 19+ Double.toString 은 최단 왕복 자릿수(Ryu 계열) — Python repr 과 같은 자릿수
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int exp = bd.precision() - bd.scale() - 1;
        String sign = d < 0 ? "-" : "";
        if (exp >= -5 + 1 && exp < 16) {
            return sign + fixed(digits, exp, true);
        }
        String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mant + "e" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
    }

    /** 유효숫자 digits 와 십진 지수 exp 로 고정소수 문자열 */
    private static String fixed(String digits, int exp, boolean forceFraction) {
        StringBuilder sb = new StringBuilder();
        if (exp < 0) {
            sb.append("0.");
            sb.append("0".repeat(-exp - 1));
            sb.append(digits);
        } else if (digits.length() <= exp + 1) {
            sb.append(digits).append("0".repeat(exp + 1 - digits.length()));
            if (forceFraction) {
                sb.append(".0");
            }
        } else {
            sb.append(digits, 0, exp + 1).append('.').append(digits.substring(exp + 1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ format(v, spec)

    /**
     * @throws PyOps.PyValueError 스펙이 값 타입과 맞지 않을 때(호출 측은 str(v) 로 대체 — format_message 동작)
     */
    public static String format(Object v, String spec) {
        if (spec == null || spec.isEmpty()) {
            return str(v);
        }
        Matcher m = SPEC.matcher(spec);
        if (!m.matches()) {
            throw new PyOps.PyValueError("Invalid format specifier '" + spec + "'");
        }
        String fill = m.group(1) != null ? m.group(1) : (m.group(5) != null ? "0" : " ");
        String align = m.group(2);
        String sign = m.group(3) != null ? m.group(3) : "-";
        int width = m.group(6) != null ? Integer.parseInt(m.group(6)) : 0;
        String group = m.group(7);
        Integer prec = m.group(8) != null ? Integer.valueOf(m.group(8)) : null;
        String type = m.group(9);
        if (m.group(5) != null && align == null) {
            align = "=";
        }
        String body;
        boolean numeric;
        if (v instanceof String s) {
            if (type != null && !type.equals("s")) {
                throw new PyOps.PyValueError("Unknown format code '" + type + "' for object of type 'str'");
            }
            if (group != null) {
                throw new PyOps.PyValueError("Cannot specify ',' with 's'.");
            }
            body = prec != null && s.length() > prec ? s.substring(0, prec) : s;
            return pad(body, width, fill, align == null ? "<" : align, "");
        }
        if (v instanceof Boolean b && type == null && prec == null && group == null) {
            return pad(b ? "True" : "False", width, fill, align == null ? "<" : align, "");
        }
        if (!(v instanceof Long || v instanceof Double || v instanceof Boolean)) {
            if (type == null && prec == null && group == null) {
                return pad(str(v), width, fill, align == null ? "<" : align, "");
            }
            throw new PyOps.PyValueError("unsupported format string passed to " + PyOps.typeName(v) + ".__format__");
        }
        numeric = true;
        boolean isInt = v instanceof Long || v instanceof Boolean;
        double d = PyOps.asDouble(v);
        String t = type;
        if (t == null) {
            t = isInt ? "d" : (prec == null ? "r" : "g~");
        }
        String signStr;
        boolean negative;
        switch (t) {
            case "d", "n" -> {
                if (!isInt) {
                    throw new PyOps.PyValueError("Unknown format code 'd' for object of type 'float'");
                }
                long l = PyOps.asLong(v);
                negative = l < 0;
                body = group(Long.toString(Math.abs(l)), group);
            }
            case "f", "F" -> {
                negative = d < 0 || (d == 0.0 && 1 / d < 0);
                body = special(d, t.equals("F"));
                if (body == null) {
                    body = groupFixed(new BigDecimal(Math.abs(d)).setScale(prec == null ? 6 : prec, RoundingMode.HALF_EVEN)
                            .toPlainString(), group);
                }
            }
            case "%" -> {
                double x = d * 100;
                negative = x < 0 || (x == 0.0 && 1 / x < 0);
                body = special(x, false);
                if (body == null) {
                    body = groupFixed(new BigDecimal(Math.abs(x)).setScale(prec == null ? 6 : prec, RoundingMode.HALF_EVEN)
                            .toPlainString(), group);
                }
                body = body + "%";
            }
            case "e", "E" -> {
                negative = d < 0 || (d == 0.0 && 1 / d < 0);
                body = special(d, t.equals("E"));
                if (body == null) {
                    body = sci(Math.abs(d), prec == null ? 6 : prec, false);
                    if (t.equals("E")) {
                        body = body.toUpperCase(java.util.Locale.ROOT);
                    }
                }
            }
            case "g", "G", "g~" -> {
                negative = d < 0 || (d == 0.0 && 1 / d < 0);
                body = special(d, t.equals("G"));
                if (body == null) {
                    int p = prec == null ? 6 : (prec == 0 ? 1 : prec);
                    body = general(Math.abs(d), p, t.equals("g~"));
                    if (t.equals("G")) {
                        body = body.toUpperCase(java.util.Locale.ROOT);
                    }
                    body = groupFixed(body, group);
                }
            }
            case "r" -> {   // 타입 없는 float(정밀도 없음) = repr
                negative = d < 0 || (d == 0.0 && 1 / d < 0);
                String r = floatRepr(Math.abs(d));
                body = groupFixed(r, group);
            }
            default -> throw new PyOps.PyValueError("Unknown format code '" + t + "'");
        }
        if (negative) {
            signStr = "-";
        } else if (sign.equals("+")) {
            signStr = "+";
        } else if (sign.equals(" ")) {
            signStr = " ";
        } else {
            signStr = "";
        }
        return numeric ? pad(body, width, fill, align == null ? ">" : align, signStr) : body;
    }

    private static String special(double d, boolean upper) {
        String s = null;
        if (Double.isNaN(d)) {
            s = "nan";
        } else if (Double.isInfinite(d)) {
            s = "inf";
        }
        return s == null ? null : (upper ? s.toUpperCase(java.util.Locale.ROOT) : s);
    }

    private static String sci(double x, int p, boolean strip) {
        if (x == 0.0) {
            String m = p > 0 && !strip ? "0." + "0".repeat(p) : "0";
            return m + "e+00";
        }
        BigDecimal bd = new BigDecimal(x).round(new MathContext(p + 1, RoundingMode.HALF_EVEN));
        int exp = bd.precision() - bd.scale() - 1;
        String digits = bd.unscaledValue().toString();
        if (digits.length() < p + 1) {
            digits = digits + "0".repeat(p + 1 - digits.length());
        }
        digits = digits.substring(0, p + 1);
        String frac = digits.substring(1);
        if (strip) {
            frac = frac.replaceAll("0+$", "");
        }
        String mant = frac.isEmpty() ? digits.substring(0, 1) : digits.charAt(0) + "." + frac;
        return mant + "e" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
    }

    /** 'g' — 유효숫자 p 자리, -4 ≤ exp < p 이면 고정소수, 뒤 0 제거. noType=true 는 스펙 타입 생략(소수점 1자리 이상 유지) */
    private static String general(double x, int p, boolean noType) {
        if (x == 0.0) {
            return noType ? "0.0" : "0";
        }
        BigDecimal bd = new BigDecimal(x).round(new MathContext(p, RoundingMode.HALF_EVEN));
        int exp = bd.precision() - bd.scale() - 1;
        if (exp >= -4 && exp < p) {
            String s = bd.setScale(Math.max(0, p - 1 - exp), RoundingMode.HALF_EVEN).toPlainString();
            if (s.contains(".")) {
                s = s.replaceAll("0+$", "");
                if (s.endsWith(".")) {
                    s = noType ? s + "0" : s.substring(0, s.length() - 1);
                }
            } else if (noType) {
                s = s + ".0";
            }
            return s;
        }
        return sci(x, p - 1, true);
    }

    private static String group(String intDigits, String sep) {
        if (sep == null) {
            return intDigits;
        }
        StringBuilder sb = new StringBuilder();
        int n = intDigits.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) {
                sb.append(sep);
            }
            sb.append(intDigits.charAt(i));
        }
        return sb.toString();
    }

    private static String groupFixed(String plain, String sep) {
        if (sep == null) {
            return plain;
        }
        int end = 0;
        while (end < plain.length() && Character.isDigit(plain.charAt(end))) {
            end++;
        }
        return group(plain.substring(0, end), sep) + plain.substring(end);
    }

    private static String pad(String body, int width, String fill, String align, String sign) {
        int len = sign.length() + body.codePointCount(0, body.length());
        if (len >= width) {
            return sign + body;
        }
        String f = fill.repeat(width - len);
        return switch (align) {
            case "<" -> sign + body + f;
            case "^" -> {
                int left = (width - len) / 2;
                yield fill.repeat(left) + sign + body + fill.repeat(width - len - left);
            }
            case "=" -> sign + f + body;
            default -> f + sign + body;
        };
    }
}
