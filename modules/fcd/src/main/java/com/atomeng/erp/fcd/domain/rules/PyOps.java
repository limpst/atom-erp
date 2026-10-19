package com.atomeng.erp.fcd.domain.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Python 값 의미론 이식 — 룰셋 표현식이 Python 프로토타입(atomeng_common.rules.expr)과 <b>같은 결과</b>를 내도록 한다.
 *
 * <p>값 모델: {@code null}=None, {@link Boolean}=bool, {@link Long}=int, {@link Double}=float, {@link String}=str,
 * {@link List}=list/tuple, {@link Set}=frozenset, {@link Map}=dict. 외부(JSON·YAML)에서 들어온 값은
 * {@link #normalize(Object)} 로 이 모델로 바꾼다.
 */
public final class PyOps {

    private PyOps() {}

    /** Python TypeError — 비교·함수 호출 경로에서는 잡혀서 False/None 이 되고, 산술 경로에서는 전파된다 */
    public static final class PyTypeError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PyTypeError(String m) {
            super(m);
        }
    }

    /** Python ValueError — 함수 호출 경로에서 잡혀 None */
    public static final class PyValueError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PyValueError(String m) {
            super(m);
        }
    }

    /** ZeroDivisionError — Python 평가기는 잡지 않으므로 그대로 전파 */
    public static final class PyZeroDivision extends ArithmeticException {
        private static final long serialVersionUID = 1L;

        public PyZeroDivision() {
            super("division by zero");
        }
    }

    // ------------------------------------------------------------------ 정규화

    @SuppressWarnings("unchecked")
    public static Object normalize(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Long || v instanceof Double) {
            return v;
        }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof java.math.BigInteger b) {
            return b.longValue();
        }
        if (v instanceof Float f) {
            return f.doubleValue();
        }
        if (v instanceof BigDecimal b) {
            return b.scale() <= 0 && b.stripTrailingZeros().scale() <= 0 ? (Object) b.longValueExact() : b.doubleValue();
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        }
        if (v instanceof java.time.temporal.TemporalAccessor t) {
            return t.toString();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), normalize(x)));
            return out;
        }
        if (v instanceof Set<?> s) {
            Set<Object> out = new java.util.LinkedHashSet<>();
            s.forEach(x -> out.add(normalize(x)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            c.forEach(x -> out.add(normalize(x)));
            return out;
        }
        if (v.getClass().isArray() && v instanceof Object[] arr) {
            List<Object> out = new ArrayList<>(arr.length);
            for (Object x : arr) {
                out.add(normalize(x));
            }
            return out;
        }
        return v.toString();
    }

    // ------------------------------------------------------------------ 진리값 · 동등 · 대소

    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Long l) {
            return l != 0;
        }
        if (v instanceof Double d) {
            return d != 0.0;   // NaN 은 참
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    static boolean isNum(Object v) {
        return v instanceof Long || v instanceof Double || v instanceof Boolean;
    }

    static boolean isIntLike(Object v) {
        return v instanceof Long || v instanceof Boolean;
    }

    static long asLong(Object v) {
        if (v instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        return (Long) v;
    }

    static double asDouble(Object v) {
        if (v instanceof Double d) {
            return d;
        }
        return asLong(v);
    }

    public static boolean pyEq(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (isNum(a) && isNum(b)) {
            if (isIntLike(a) && isIntLike(b)) {
                return asLong(a) == asLong(b);
            }
            return asDouble(a) == asDouble(b);
        }
        if (a instanceof String x && b instanceof String y) {
            return x.equals(y);
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!pyEq(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Set<?> x && b instanceof Set<?> y) {
            return x.size() == y.size() && x.stream().allMatch(e -> contains(y, e));
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            for (var e : x.entrySet()) {
                if (!pyEq(e.getValue(), y.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Python 대소 비교. 타입이 맞지 않으면 TypeError */
    public static int pyCompare(Object a, Object b) {
        if (a != null && b != null) {
            if (isNum(a) && isNum(b)) {
                if (isIntLike(a) && isIntLike(b)) {
                    return Long.compare(asLong(a), asLong(b));
                }
                double x = asDouble(a);
                double y = asDouble(b);
                if (Double.isNaN(x) || Double.isNaN(y)) {
                    return Integer.MIN_VALUE;   // NaN: 모든 대소 비교 거짓
                }
                return x < y ? -1 : (x > y ? 1 : 0);
            }
            if (a instanceof String x && b instanceof String y) {
                return Integer.signum(compareCodePoints(x, y));
            }
            if (a instanceof List<?> x && b instanceof List<?> y) {
                for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                    if (!pyEq(x.get(i), y.get(i))) {
                        return pyCompare(x.get(i), y.get(i));
                    }
                }
                return Integer.compare(x.size(), y.size());
            }
        }
        throw new PyTypeError("'<' not supported between " + typeName(a) + " and " + typeName(b));
    }

    private static int compareCodePoints(String x, String y) {
        int i = 0;
        int j = 0;
        while (i < x.length() && j < y.length()) {
            int a = x.codePointAt(i);
            int b = y.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        if (i < x.length()) {
            return 1;
        }
        return j < y.length() ? -1 : 0;
    }

    public static boolean lt(Object a, Object b) {
        int c = pyCompare(a, b);
        return c != Integer.MIN_VALUE && c < 0;
    }

    public static boolean gt(Object a, Object b) {
        int c = pyCompare(a, b);
        return c != Integer.MIN_VALUE && c > 0;
    }

    public static boolean le(Object a, Object b) {
        int c = pyCompare(a, b);
        return c != Integer.MIN_VALUE && c <= 0;
    }

    public static boolean ge(Object a, Object b) {
        int c = pyCompare(a, b);
        return c != Integer.MIN_VALUE && c >= 0;
    }

    public static boolean is(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return x.equals(y);
        }
        return a == b;
    }

    /** {@code a in b} */
    public static boolean contains(Object b, Object a) {
        if (b instanceof Map<?, ?> m) {
            return a instanceof String && m.containsKey(a);
        }
        if (b instanceof Collection<?> c) {
            for (Object x : c) {
                if (pyEq(a, x)) {
                    return true;
                }
            }
            return false;
        }
        if (b instanceof String s) {
            if (a instanceof String x) {
                return s.contains(x);
            }
            throw new PyTypeError("'in <string>' requires string as left operand");
        }
        throw new PyTypeError("argument of type '" + typeName(b) + "' is not iterable");
    }

    public static String typeName(Object v) {
        if (v == null) {
            return "NoneType";
        }
        if (v instanceof Boolean) {
            return "bool";
        }
        if (v instanceof Long) {
            return "int";
        }
        if (v instanceof Double) {
            return "float";
        }
        if (v instanceof String) {
            return "str";
        }
        if (v instanceof List) {
            return "list";
        }
        if (v instanceof Set) {
            return "frozenset";
        }
        if (v instanceof Map) {
            return "dict";
        }
        return v.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ 산술

    public static Object add(Object a, Object b) {
        if (isNum(a) && isNum(b)) {
            return isIntLike(a) && isIntLike(b) ? (Object) (asLong(a) + asLong(b)) : (Object) (asDouble(a) + asDouble(b));
        }
        if (a instanceof String x && b instanceof String y) {
            return x + y;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            List<Object> out = new ArrayList<>(x);
            out.addAll(y);
            return out;
        }
        throw new PyTypeError("unsupported operand type(s) for +: " + typeName(a) + " and " + typeName(b));
    }

    public static Object sub(Object a, Object b) {
        requireNums(a, b, "-");
        return isIntLike(a) && isIntLike(b) ? (Object) (asLong(a) - asLong(b)) : (Object) (asDouble(a) - asDouble(b));
    }

    public static Object mul(Object a, Object b) {
        requireNums(a, b, "*");
        return isIntLike(a) && isIntLike(b) ? (Object) (asLong(a) * asLong(b)) : (Object) (asDouble(a) * asDouble(b));
    }

    public static Object div(Object a, Object b) {
        requireNums(a, b, "/");
        double y = asDouble(b);
        if (y == 0.0) {
            throw new PyZeroDivision();
        }
        if (isIntLike(a) && isIntLike(b)) {
            // int / int: 정확한 나눗셈의 가장 가까운 double (Python true division)
            return new BigDecimal(asLong(a)).divide(new BigDecimal(asLong(b)), java.math.MathContext.DECIMAL128)
                    .doubleValue();
        }
        return asDouble(a) / y;
    }

    public static Object floorDiv(Object a, Object b) {
        requireNums(a, b, "//");
        if (isIntLike(a) && isIntLike(b)) {
            if (asLong(b) == 0) {
                throw new PyZeroDivision();
            }
            return Math.floorDiv(asLong(a), asLong(b));
        }
        double x = asDouble(a);
        double y = asDouble(b);
        if (y == 0.0) {
            throw new PyZeroDivision();
        }
        double mod = pyFmod(x, y);
        return Math.floor((x - mod) / y + 0.0);
    }

    public static Object mod(Object a, Object b) {
        if (a instanceof String) {
            throw new PyTypeError("printf-style formatting not allowed");
        }
        requireNums(a, b, "%");
        if (isIntLike(a) && isIntLike(b)) {
            if (asLong(b) == 0) {
                throw new PyZeroDivision();
            }
            return Math.floorMod(asLong(a), asLong(b));
        }
        double y = asDouble(b);
        if (y == 0.0) {
            throw new PyZeroDivision();
        }
        return pyFmod(asDouble(a), y);
    }

    /** CPython float_rem: fmod 후 부호를 제수 쪽으로 맞춤 */
    static double pyFmod(double x, double y) {
        double mod = x % y;
        if (mod != 0.0) {
            if ((y < 0) != (mod < 0)) {
                mod += y;
            }
        } else {
            mod = Math.copySign(0.0, y);
        }
        return mod;
    }

    public static Object pow(Object a, Object b) {
        requireNums(a, b, "**");
        if (isIntLike(a) && isIntLike(b) && asLong(b) >= 0) {
            long base = asLong(a);
            long e = asLong(b);
            // SEC-FCD-005: |base| ≤ 1 은 넘침 없이 e 번 반복하므로(1 ** 2^63-1 → 9e18 회) 결과를 바로 계산.
            // |base| ≥ 2 는 63 회 안에 multiplyExact 가 넘침을 던지므로 반복 횟수가 자연히 제한된다.
            if (base == 0) {
                return e == 0 ? 1L : 0L;
            }
            if (base == 1) {
                return 1L;
            }
            if (base == -1) {
                return (e & 1L) == 0 ? 1L : -1L;
            }
            long r = 1;
            for (long i = 0; i < e; i++) {
                r = Math.multiplyExact(r, base);
            }
            return r;
        }
        double x = asDouble(a);
        double y = asDouble(b);
        if (x == 0.0 && y < 0) {
            throw new PyZeroDivision();
        }
        return Math.pow(x, y);
    }

    public static Object neg(Object a) {
        if (a instanceof Double d) {
            return -d;
        }
        if (isIntLike(a)) {
            return -asLong(a);
        }
        throw new PyTypeError("bad operand type for unary -: '" + typeName(a) + "'");
    }

    public static Object pos(Object a) {
        if (a instanceof Double) {
            return a;
        }
        if (isIntLike(a)) {
            return asLong(a);
        }
        throw new PyTypeError("bad operand type for unary +: '" + typeName(a) + "'");
    }

    private static void requireNums(Object a, Object b, String op) {
        if (!isNum(a) || !isNum(b)) {
            throw new PyTypeError("unsupported operand type(s) for " + op + ": " + typeName(a) + " and " + typeName(b));
        }
    }

    // ------------------------------------------------------------------ 반올림 · 변환

    /** Python round(x) — 짝수 반올림, 정수 반환 */
    public static long roundHalfEven(double x) {
        if (Double.isNaN(x)) {
            throw new PyValueError("cannot convert float NaN to integer");
        }
        if (Double.isInfinite(x)) {
            throw new ArithmeticException("cannot convert float infinity to integer");
        }
        return (long) Math.rint(x);
    }

    /** CPython floatobject.c: (DBL_MANT_DIG - DBL_MIN_EXP) * 0.30103 = 323, -(DBL_MAX_EXP + 1) * 0.30103 = -308 */
    static final long ROUND_NDIGITS_MAX = 323;
    static final long ROUND_NDIGITS_MIN = -308;

    /** Python round(int, n) — n ≥ 0 은 그대로, 음수는 10^-n 단위 짝수 반올림. |long| < 10^19 이므로 n ≤ -20 은 0. */
    public static long roundInt(long l, long n) {
        if (n >= 0) {
            return l;
        }
        if (n < -19) {
            return 0L;
        }
        return new BigDecimal(l).setScale((int) n, RoundingMode.HALF_EVEN).longValue();
    }

    /** Python round(x, n) — 이진값 그대로 십진 짝수 반올림(CPython 의 correctly-rounded 방식과 같음) */
    public static double round(double x, long n) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        // CPython float.__round__ 과 같은 경계(NDIGITS_MAX/MIN): 이 밖은 계산 없이 x 또는 ±0.0.
        // SEC-FCD-006: round(0.5, 999999999) 가 10^999999999 자리 BigDecimal 을 만들던 DoS 차단.
        if (n > ROUND_NDIGITS_MAX) {
            return x;
        }
        if (n < ROUND_NDIGITS_MIN) {
            return 0.0 * x;
        }
        return new BigDecimal(x).setScale((int) n, RoundingMode.HALF_EVEN).doubleValue();
    }

    public static double toFloat(Object v) {
        if (v == null) {
            throw new PyTypeError("float() argument must be a string or a real number, not 'NoneType'");
        }
        if (isNum(v)) {
            return asDouble(v);
        }
        if (v instanceof String s) {
            String t = s.strip().replace("_", "").toLowerCase(java.util.Locale.ROOT);
            switch (t) {
                case "nan", "+nan", "-nan" -> {
                    return Double.NaN;
                }
                case "inf", "+inf", "infinity", "+infinity" -> {
                    return Double.POSITIVE_INFINITY;
                }
                case "-inf", "-infinity" -> {
                    return Double.NEGATIVE_INFINITY;
                }
                default -> {
                    try {
                        if (!t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)(e[+-]?\\d+)?")) {
                            throw new NumberFormatException(t);
                        }
                        return Double.parseDouble(t);
                    } catch (NumberFormatException e) {
                        throw new PyValueError("could not convert string to float: '" + s + "'");
                    }
                }
            }
        }
        throw new PyTypeError("float() argument must be a string or a real number, not '" + typeName(v) + "'");
    }

    public static long toInt(Object v) {
        if (v == null) {
            throw new PyTypeError("int() argument must be a string or a real number, not 'NoneType'");
        }
        if (v instanceof Double d) {
            if (Double.isNaN(d)) {
                throw new PyValueError("cannot convert float NaN to integer");
            }
            return (long) (d < 0 ? Math.ceil(d) : Math.floor(d));
        }
        if (isIntLike(v)) {
            return asLong(v);
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.strip().replace("_", ""));
            } catch (NumberFormatException e) {
                throw new PyValueError("invalid literal for int() with base 10: '" + s + "'");
            }
        }
        throw new PyTypeError("int() argument must be a string or a real number, not '" + typeName(v) + "'");
    }

    public static Iterable<?> iter(Object v) {
        if (v instanceof Collection<?> c) {
            return c;
        }
        if (v instanceof Map<?, ?> m) {
            return m.keySet();
        }
        if (v instanceof String s) {
            List<Object> out = new ArrayList<>();
            s.codePoints().forEach(cp -> out.add(new String(Character.toChars(cp))));
            return out;
        }
        throw new PyTypeError("'" + typeName(v) + "' object is not iterable");
    }

    /** min/max — 첫 번째 극값을 그대로 반환(Python 과 같음) */
    public static Object minMax(List<Object> args, boolean max) {
        Iterator<?> it;
        if (args.size() == 1) {
            it = iter(args.get(0)).iterator();
        } else if (args.isEmpty()) {
            throw new PyTypeError("expected at least 1 argument, got 0");
        } else {
            it = args.iterator();
        }
        if (!it.hasNext()) {
            throw new PyValueError("arg is an empty sequence");
        }
        Object best = it.next();
        while (it.hasNext()) {
            Object v = it.next();
            if (max ? gtStrict(v, best) : gtStrict(best, v)) {
                best = v;
            }
        }
        return best;
    }

    private static boolean gtStrict(Object a, Object b) {
        int c = pyCompare(a, b);
        return c != Integer.MIN_VALUE && c > 0;
    }

    public static boolean isNaN(Object v) {
        return v instanceof Double d && d.isNaN();
    }
}
