package com.atomeng.erp.fcd.domain.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 화이트리스트 안전 표현식 평가기 — Python 프로토타입 {@code atomeng_common.rules.expr} 의 Java 이식.
 * <b>eval·스크립트 엔진을 쓰지 않는다</b>: 직접 만든 토크나이저·재귀 하강 파서로 Python 식 부분집합만 받아
 * 클로저 트리로 컴파일한다(재무제표 AI v1.0 P2 "eval 금지, 화이트리스트 AST").
 *
 * <p>허용: and/or/not, 비교(체이닝·in·not in·is·is not), + - * / // % **, 단항 - +, 조건식(a if c else b),
 * 속성(ns.field), 첨자(x['k']), 리스트·튜플·집합 리터럴, 화이트리스트 함수
 * (min max abs round len clip log exp sqrt float int bool any all isnull coalesce).
 * 금지: 그 밖의 함수 호출, 람다, 컴프리헨션, 딕셔너리 리터럴, 슬라이스, 비트 연산, '_' 로 시작하는 이름·속성·키.
 */
public final class PyExpr {

    /** 컴파일된 식 */
    @FunctionalInterface
    public interface Node {
        Object eval(Map<String, Object> env);
    }

    public static final class ExprError extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public ExprError(String m) {
            super(m);
        }
    }

    public static final Set<String> SAFE_FUNCS = Set.of("min", "max", "abs", "round", "len", "clip", "log", "exp",
            "sqrt", "float", "int", "bool", "any", "all", "isnull", "coalesce");

    private static final Map<String, Node> CACHE = new ConcurrentHashMap<>();

    private PyExpr() {}

    /** SEC-FCD-003: 식 길이 · 중첩 깊이 상한 — 깊은 괄호로 StackOverflowError(500) 를 내는 입력 차단 */
    public static final int MAX_EXPR_LENGTH = 4000;
    public static final int MAX_NESTING_DEPTH = 200; // 괄호 한 단 = test · notTest · factor 3 단 → 약 66 단 괄호

    public static Node compile(String src) {
        if (src != null && src.length() > MAX_EXPR_LENGTH) {
            throw new ExprError("expression too long: " + src.length() + " > " + MAX_EXPR_LENGTH);
        }
        String norm = String.join(" ", src.trim().split("\\s+"));
        return CACHE.computeIfAbsent(norm, s -> new Parser(new Lexer(s).tokens()).parseAll());
    }

    public static Object evaluate(String src, Map<String, Object> env) {
        return compile(src).eval(env);
    }

    /** 속성 접근(_get): dict 면 키(없으면 None), 그 밖은 None */
    public static Object getAttr(Object obj, String name) {
        if (name.startsWith("_")) {
            throw new ExprError("private attribute access is not allowed: " + name);
        }
        if (obj instanceof Map<?, ?> m) {
            return m.get(name);
        }
        return null;
    }

    // ================================================================== 토크나이저

    enum T { NUM, STR, NAME, OP, END }

    record Tok(T type, String text, Object value) {}

    static final class Lexer {
        private final String s;
        private int i;

        Lexer(String s) {
            this.s = s;
        }

        List<Tok> tokens() {
            List<Tok> out = new ArrayList<>();
            while (true) {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                if (i >= s.length()) {
                    out.add(new Tok(T.END, "", null));
                    return out;
                }
                char c = s.charAt(i);
                if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                    out.add(number());
                } else if (c == '\'' || c == '"') {
                    out.add(string(c));
                } else if (Character.isLetter(c) || c == '_') {
                    int st = i;
                    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                        i++;
                    }
                    String w = s.substring(st, i);
                    if (i < s.length() && (s.charAt(i) == '\'' || s.charAt(i) == '"')
                            && w.matches("(?i)[rbuf]{1,2}")) {
                        throw new ExprError("syntax not allowed: string prefix " + w);
                    }
                    out.add(new Tok(T.NAME, w, null));
                } else {
                    String[] ops = {"**", "//", "==", "!=", "<=", ">=", "<<", ">>", "->", ":=",
                            "(", ")", "[", "]", "{", "}", ",", ".", ":", "+", "-", "*", "/", "%", "<", ">", "|",
                            "&", "^", "~", "@", "=", ";"};
                    String hit = null;
                    for (String op : ops) {
                        if (s.startsWith(op, i)) {
                            hit = op;
                            break;
                        }
                    }
                    if (hit == null) {
                        throw new ExprError("syntax error: unexpected character '" + c + "'");
                    }
                    i += hit.length();
                    out.add(new Tok(T.OP, hit, null));
                }
            }
        }

        private Tok number() {
            int st = i;
            boolean isFloat = false;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            if (i < s.length() && s.charAt(i) == '.') {
                isFloat = true;
                i++;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int save = i;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                if (i < s.length() && Character.isDigit(s.charAt(i))) {
                    isFloat = true;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                } else {
                    i = save;
                }
            }
            if (i < s.length() && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '_')) {
                throw new ExprError("syntax error: invalid number literal");
            }
            String txt = s.substring(st, i).replace("_", "");
            Object v = isFloat ? (Object) Double.parseDouble(txt) : (Object) Long.parseLong(txt);
            return new Tok(T.NUM, txt, v);
        }

        private Tok string(char q) {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != q) {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '\\' -> sb.append('\\');
                        case '\'' -> sb.append('\'');
                        case '"' -> sb.append('"');
                        case '0' -> sb.append('\0');
                        default -> sb.append('\\').append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            if (i >= s.length()) {
                throw new ExprError("syntax error: unterminated string literal");
            }
            i++;
            return new Tok(T.STR, sb.toString(), sb.toString());
        }
    }

    // ================================================================== 파서 → 노드

    private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "in", "is", "if", "else", "lambda",
            "for", "None", "True", "False", "await", "yield", "import", "from", "def", "class", "return", "del",
            "global", "nonlocal", "assert", "async", "with", "while", "try", "except", "finally", "raise", "pass",
            "break", "continue", "as", "elif");

    /** 리터럴 판별용 표식 */
    private record Const(Object value) implements Node {
        @Override
        public Object eval(Map<String, Object> env) {
            return value;
        }
    }

    static final class Parser {
        private final List<Tok> toks;
        private int p;
        private int depth;

        private void enter() {
            if (++depth > MAX_NESTING_DEPTH) {
                throw new ExprError("expression nested too deeply (max " + MAX_NESTING_DEPTH + ")");
            }
        }

        Parser(List<Tok> toks) {
            this.toks = toks;
        }

        private Tok peek() {
            return toks.get(p);
        }

        private boolean isOp(String op) {
            return peek().type() == T.OP && peek().text().equals(op);
        }

        private boolean isKw(String kw) {
            return peek().type() == T.NAME && peek().text().equals(kw);
        }

        private Tok next() {
            return toks.get(p++);
        }

        private void expectOp(String op) {
            if (!isOp(op)) {
                throw new ExprError("syntax error: expected '" + op + "' near '" + peek().text() + "'");
            }
            p++;
        }

        Node parseAll() {
            Node n = testList();
            if (peek().type() != T.END) {
                if (isOp("=") || isOp(":=")) {
                    throw new ExprError("syntax not allowed: assignment");
                }
                throw new ExprError("syntax error near '" + peek().text() + "'");
            }
            return n;
        }

        /** 최상위 a, b → 튜플 */
        private Node testList() {
            Node first = test();
            if (!isOp(",")) {
                return first;
            }
            List<Node> items = new ArrayList<>();
            items.add(first);
            while (isOp(",")) {
                p++;
                if (peek().type() == T.END || isOp(")")) {
                    break;
                }
                items.add(test());
            }
            return seq(items, "tuple");
        }

        private Node test() {
            enter();
            try {
                return testInner();
            } finally {
                depth--;
            }
        }

        private Node testInner() {
            if (isKw("lambda")) {
                throw new ExprError("syntax not allowed: Lambda");
            }
            Node body = orTest();
            if (isKw("if")) {
                p++;
                Node cond = orTest();
                if (!isKw("else")) {
                    throw new ExprError("syntax error: expected 'else'");
                }
                p++;
                Node other = test();
                return env -> PyOps.truthy(cond.eval(env)) ? body.eval(env) : other.eval(env);
            }
            return body;
        }

        private Node orTest() {
            Node left = andTest();
            if (!isKw("or")) {
                return left;
            }
            List<Node> parts = new ArrayList<>(List.of(left));
            while (isKw("or")) {
                p++;
                parts.add(andTest());
            }
            return env -> {
                Object r = Boolean.FALSE;
                for (Node n : parts) {
                    r = n.eval(env);
                    if (PyOps.truthy(r)) {
                        return r;
                    }
                }
                return r;
            };
        }

        private Node andTest() {
            Node left = notTest();
            if (!isKw("and")) {
                return left;
            }
            List<Node> parts = new ArrayList<>(List.of(left));
            while (isKw("and")) {
                p++;
                parts.add(notTest());
            }
            return env -> {
                Object r = Boolean.TRUE;
                for (Node n : parts) {
                    r = n.eval(env);
                    if (!PyOps.truthy(r)) {
                        return r;
                    }
                }
                return r;
            };
        }

        private Node notTest() {
            enter();
            try {
                return notTestInner();
            } finally {
                depth--;
            }
        }

        private Node notTestInner() {
            if (isKw("not")) {
                p++;
                Node x = notTest();
                return env -> !PyOps.truthy(x.eval(env));
            }
            return comparison();
        }

        private String compOp() {
            Tok t = peek();
            if (t.type() == T.OP) {
                switch (t.text()) {
                    case "<", ">", "==", ">=", "<=", "!=" -> {
                        p++;
                        return t.text();
                    }
                    default -> {
                        return null;
                    }
                }
            }
            if (t.type() == T.NAME) {
                if (t.text().equals("in")) {
                    p++;
                    return "in";
                }
                if (t.text().equals("not") && toks.get(p + 1).type() == T.NAME && toks.get(p + 1).text().equals("in")) {
                    p += 2;
                    return "not in";
                }
                if (t.text().equals("is")) {
                    p++;
                    if (isKw("not")) {
                        p++;
                        return "is not";
                    }
                    return "is";
                }
            }
            return null;
        }

        private Node comparison() {
            Node left = bitOr();
            List<String> ops = new ArrayList<>();
            List<Node> comps = new ArrayList<>();
            String op;
            while ((op = compOp()) != null) {
                ops.add(op);
                comps.add(bitOr());
            }
            if (ops.isEmpty()) {
                return left;
            }
            return env -> {
                Object a = left.eval(env);
                for (int k = 0; k < ops.size(); k++) {
                    String o = ops.get(k);
                    Object b = comps.get(k).eval(env);
                    boolean eqLike = o.equals("is") || o.equals("is not") || o.equals("==") || o.equals("!=");
                    if ((a == null || b == null) && !eqLike) {
                        return false;   // 결측과의 대소·포함 비교는 거짓(판정 보류)
                    }
                    boolean ok;
                    try {
                        ok = switch (o) {
                            case "==" -> PyOps.pyEq(a, b);
                            case "!=" -> !PyOps.pyEq(a, b);
                            case "<" -> PyOps.lt(a, b);
                            case ">" -> PyOps.gt(a, b);
                            case "<=" -> PyOps.le(a, b);
                            case ">=" -> PyOps.ge(a, b);
                            case "in" -> PyOps.contains(b, a);
                            case "not in" -> !PyOps.contains(b, a);
                            case "is" -> PyOps.is(a, b);
                            case "is not" -> !PyOps.is(a, b);
                            default -> throw new IllegalStateException(o);
                        };
                    } catch (PyOps.PyTypeError e) {
                        return false;
                    }
                    if (!ok) {
                        return false;
                    }
                    a = b;
                }
                return true;
            };
        }

        private Node bitOr() {
            Node n = arith();
            if (isOp("|") || isOp("&") || isOp("^") || isOp("<<") || isOp(">>")) {
                throw new ExprError("operator not allowed: " + peek().text());
            }
            return n;
        }

        private Node arith() {
            Node left = term();
            while (isOp("+") || isOp("-")) {
                String op = next().text();
                Node l = left;
                Node r = term();
                left = binary(l, r, op.equals("+") ? PyOps::add : PyOps::sub);
            }
            return left;
        }

        private Node term() {
            Node left = factor();
            while (isOp("*") || isOp("/") || isOp("//") || isOp("%") || isOp("@")) {
                String op = next().text();
                if (op.equals("@")) {
                    throw new ExprError("operator not allowed: MatMult");
                }
                Node l = left;
                Node r = factor();
                left = binary(l, r, switch (op) {
                    case "*" -> PyOps::mul;
                    case "/" -> PyOps::div;
                    case "//" -> PyOps::floorDiv;
                    default -> PyOps::mod;
                });
            }
            return left;
        }

        private static Node binary(Node l, Node r, java.util.function.BinaryOperator<Object> f) {
            return env -> {
                Object a = l.eval(env);
                Object b = r.eval(env);
                if (a == null || b == null) {
                    return null;
                }
                return f.apply(a, b);
            };
        }

        private Node factor() {
            enter();
            try {
                return factorInner();
            } finally {
                depth--;
            }
        }

        private Node factorInner() {
            if (isOp("-")) {
                p++;
                Node x = factor();
                return env -> PyOps.neg(x.eval(env));
            }
            if (isOp("+")) {
                p++;
                Node x = factor();
                return env -> PyOps.pos(x.eval(env));
            }
            if (isOp("~")) {
                throw new ExprError("unary operator not allowed");
            }
            return power();
        }

        private Node power() {
            Node base = primary();
            if (isOp("**")) {
                p++;
                Node exp = factor();
                return binary(base, exp, PyOps::pow);
            }
            return base;
        }

        private Node primary() {
            Node n = atom();
            Tok nameTok = toks.get(p - 1);
            boolean isBareName = n instanceof NameNode;
            while (true) {
                if (isOp(".")) {
                    p++;
                    Tok t = next();
                    if (t.type() != T.NAME) {
                        throw new ExprError("syntax error: expected attribute name");
                    }
                    String attr = t.text();
                    if (attr.startsWith("_")) {
                        throw new ExprError("private attribute access is not allowed: " + attr);
                    }
                    Node base = n;
                    n = env -> getAttr(base.eval(env), attr);
                    isBareName = false;
                } else if (isOp("[")) {
                    p++;
                    Node key = subscript();
                    expectOp("]");
                    Node base = n;
                    n = env -> subscriptGet(base.eval(env), key.eval(env));
                    isBareName = false;
                } else if (isOp("(")) {
                    if (!isBareName || !SAFE_FUNCS.contains(nameTok.text())) {
                        throw new ExprError("function call not allowed: " + (isBareName ? nameTok.text() : "expr"));
                    }
                    p++;
                    List<Node> args = new ArrayList<>();
                    while (!isOp(")")) {
                        if (peek().type() == T.NAME && toks.get(p + 1).type() == T.OP
                                && toks.get(p + 1).text().equals("=")) {
                            throw new ExprError("keyword arguments not allowed");
                        }
                        if (isOp("*") || isOp("**")) {
                            throw new ExprError("syntax not allowed: Starred");
                        }
                        args.add(test());
                        if (isKw("for")) {
                            throw new ExprError("syntax not allowed: GeneratorExp");
                        }
                        if (!isOp(")")) {
                            expectOp(",");
                        }
                    }
                    p++;
                    n = call(nameTok.text(), args);
                    isBareName = false;
                } else {
                    return n;
                }
            }
        }

        private Node subscript() {
            if (isOp(":")) {
                throw new ExprError("syntax not allowed: Slice");
            }
            Node k = test();
            if (isOp(":")) {
                throw new ExprError("syntax not allowed: Slice");
            }
            if (isOp(",")) {
                List<Node> items = new ArrayList<>(List.of(k));
                while (isOp(",")) {
                    p++;
                    if (isOp("]")) {
                        break;
                    }
                    items.add(test());
                }
                return seq(items, "tuple");
            }
            return k;
        }

        private Node atom() {
            Tok t = next();
            switch (t.type()) {
                case NUM -> {
                    return new Const(t.value());
                }
                case STR -> {
                    StringBuilder sb = new StringBuilder(t.text());
                    while (peek().type() == T.STR) {
                        sb.append(next().text());
                    }
                    return new Const(sb.toString());
                }
                case NAME -> {
                    switch (t.text()) {
                        case "None" -> {
                            return new Const(null);
                        }
                        case "True" -> {
                            return new Const(Boolean.TRUE);
                        }
                        case "False" -> {
                            return new Const(Boolean.FALSE);
                        }
                        default -> {
                        }
                    }
                    if (KEYWORDS.contains(t.text())) {
                        throw new ExprError("syntax error near keyword '" + t.text() + "'");
                    }
                    if (t.text().startsWith("_")) {
                        throw new ExprError("name not allowed: " + t.text());
                    }
                    return new NameNode(t.text());
                }
                case OP -> {
                    switch (t.text()) {
                        case "(" -> {
                            if (isOp(")")) {
                                p++;
                                return new Const(List.of());
                            }
                            Node first = test();
                            if (isKw("for")) {
                                throw new ExprError("syntax not allowed: GeneratorExp");
                            }
                            if (isOp(")")) {
                                p++;
                                return first;
                            }
                            List<Node> items = new ArrayList<>(List.of(first));
                            while (isOp(",")) {
                                p++;
                                if (isOp(")")) {
                                    break;
                                }
                                items.add(test());
                            }
                            expectOp(")");
                            return seq(items, "tuple");
                        }
                        case "[" -> {
                            List<Node> items = new ArrayList<>();
                            while (!isOp("]")) {
                                items.add(test());
                                if (isKw("for")) {
                                    throw new ExprError("syntax not allowed: ListComp");
                                }
                                if (!isOp("]")) {
                                    expectOp(",");
                                }
                            }
                            p++;
                            return seq(items, "list");
                        }
                        case "{" -> {
                            if (isOp("}")) {
                                throw new ExprError("syntax not allowed: Dict");
                            }
                            List<Node> items = new ArrayList<>();
                            while (!isOp("}")) {
                                items.add(test());
                                if (isOp(":")) {
                                    throw new ExprError("syntax not allowed: Dict");
                                }
                                if (isKw("for")) {
                                    throw new ExprError("syntax not allowed: SetComp");
                                }
                                if (!isOp("}")) {
                                    expectOp(",");
                                }
                            }
                            p++;
                            return seq(items, "set");
                        }
                        default -> throw new ExprError("syntax error near '" + t.text() + "'");
                    }
                }
                default -> throw new ExprError("syntax error: unexpected end of expression");
            }
        }

        private static Node seq(List<Node> items, String kind) {
            boolean allConst = items.stream().allMatch(n -> n instanceof Const);
            Function<List<Object>, Object> ctor = kind.equals("set")
                    ? l -> java.util.Collections.unmodifiableSet(new LinkedHashSet<>(l))
                    : l -> java.util.Collections.unmodifiableList(new ArrayList<>(l));
            if (allConst) {
                List<Object> vals = new ArrayList<>();
                items.forEach(n -> vals.add(((Const) n).value()));
                return new Const(ctor.apply(vals));
            }
            return env -> {
                List<Object> vals = new ArrayList<>(items.size());
                for (Node n : items) {
                    vals.add(n.eval(env));
                }
                return ctor.apply(vals);
            };
        }
    }

    private record NameNode(String name) implements Node {
        @Override
        public Object eval(Map<String, Object> env) {
            return env.get(name);
        }
    }

    static Object subscriptGet(Object b, Object k) {
        if (k instanceof String s && s.startsWith("_")) {
            throw new ExprError("private key access is not allowed");
        }
        if (b instanceof Map<?, ?> m) {
            return k instanceof String ? m.get(k) : null;
        }
        if (b instanceof List<?> l && (k instanceof Long || k instanceof Boolean)) {
            long i = PyOps.asLong(k);
            if (i < 0) {
                i += l.size();
            }
            return i >= 0 && i < l.size() ? l.get((int) i) : null;
        }
        if (b instanceof String s && (k instanceof Long || k instanceof Boolean)) {
            long i = PyOps.asLong(k);
            int n = s.codePointCount(0, s.length());
            if (i < 0) {
                i += n;
            }
            if (i < 0 || i >= n) {
                return null;
            }
            int off = s.offsetByCodePoints(0, (int) i);
            return new String(Character.toChars(s.codePointAt(off)));
        }
        return null;   // KeyError · IndexError · TypeError → None
    }

    // ================================================================== 함수

    private static Node call(String fn, List<Node> args) {
        return env -> {
            List<Object> v = new ArrayList<>(args.size());
            for (Node a : args) {
                v.add(a.eval(env));
            }
            try {
                return apply(fn, v);
            } catch (PyOps.PyTypeError | PyOps.PyValueError e) {
                return null;
            }
        };
    }

    static Object apply(String fn, List<Object> a) {
        switch (fn) {
            case "min" -> {
                return PyOps.minMax(a, false);
            }
            case "max" -> {
                return PyOps.minMax(a, true);
            }
            case "abs" -> {
                arity(a, 1);
                Object x = a.get(0);
                if (x instanceof Double d) {
                    return Math.abs(d);
                }
                if (x instanceof Long || x instanceof Boolean) {
                    return Math.abs(PyOps.asLong(x));
                }
                throw new PyOps.PyTypeError("bad operand type for abs()");
            }
            case "round" -> {
                if (a.isEmpty() || a.size() > 2) {
                    throw new PyOps.PyTypeError("round() takes 1 or 2 arguments");
                }
                Object x = a.get(0);
                Object nd = a.size() == 2 ? a.get(1) : null;
                if (nd == null) {
                    if (x instanceof Double d) {
                        return PyOps.roundHalfEven(d);
                    }
                    if (x instanceof Long || x instanceof Boolean) {
                        return PyOps.asLong(x);
                    }
                    throw new PyOps.PyTypeError("type doesn't define __round__");
                }
                if (!(nd instanceof Long || nd instanceof Boolean)) {
                    throw new PyOps.PyTypeError("ndigits must be int");
                }
                long n = PyOps.asLong(nd);
                if (x instanceof Double d) {
                    return PyOps.round(d, n);
                }
                if (x instanceof Long || x instanceof Boolean) {
                    return PyOps.roundInt(PyOps.asLong(x), n);
                }
                throw new PyOps.PyTypeError("type doesn't define __round__");
            }
            case "len" -> {
                arity(a, 1);
                Object x = a.get(0);
                if (x instanceof String s) {
                    return (long) s.codePointCount(0, s.length());
                }
                if (x instanceof java.util.Collection<?> c) {
                    return (long) c.size();
                }
                if (x instanceof Map<?, ?> m) {
                    return (long) m.size();
                }
                throw new PyOps.PyTypeError("object has no len()");
            }
            case "clip" -> {
                arity(a, 3);
                Object inner = PyOps.minMax(java.util.Arrays.asList(a.get(2), a.get(0)), false);
                return PyOps.minMax(java.util.Arrays.asList(a.get(1), inner), true);
            }
            case "log" -> {
                arity(a, 1);
                Object x = a.get(0);
                if (x == null) {
                    throw new PyOps.PyTypeError("'>' not supported");
                }
                if (PyOps.gt(x, 0L)) {
                    return Math.log(PyOps.toFloat(x));
                }
                if (!(x instanceof Long || x instanceof Double || x instanceof Boolean)) {
                    throw new PyOps.PyTypeError("'>' not supported");
                }
                return Double.NEGATIVE_INFINITY;
            }
            case "exp" -> {
                arity(a, 1);
                return Math.exp(PyOps.toFloat(numArg(a.get(0))));
            }
            case "sqrt" -> {
                arity(a, 1);
                double x = PyOps.toFloat(numArg(a.get(0)));
                if (x < 0) {
                    throw new PyOps.PyValueError("math domain error");
                }
                return Math.sqrt(x);
            }
            case "float" -> {
                return a.isEmpty() ? 0.0 : PyOps.toFloat(a.get(0));
            }
            case "int" -> {
                return a.isEmpty() ? 0L : PyOps.toInt(a.get(0));
            }
            case "bool" -> {
                return !a.isEmpty() && PyOps.truthy(a.get(0));
            }
            case "any" -> {
                arity(a, 1);
                for (Object x : PyOps.iter(a.get(0))) {
                    if (PyOps.truthy(x)) {
                        return true;
                    }
                }
                return false;
            }
            case "all" -> {
                arity(a, 1);
                for (Object x : PyOps.iter(a.get(0))) {
                    if (!PyOps.truthy(x)) {
                        return false;
                    }
                }
                return true;
            }
            case "isnull" -> {
                arity(a, 1);
                return a.get(0) == null || PyOps.isNaN(a.get(0));
            }
            case "coalesce" -> {
                for (Object x : a) {
                    if (x != null && !PyOps.isNaN(x)) {
                        return x;
                    }
                }
                return null;
            }
            default -> throw new ExprError("function call not allowed: " + fn);
        }
    }

    private static Object numArg(Object x) {
        if (!(x instanceof Long || x instanceof Double || x instanceof Boolean)) {
            throw new PyOps.PyTypeError("must be real number, not " + PyOps.typeName(x));
        }
        return x;
    }

    private static void arity(List<Object> a, int n) {
        if (a.size() != n) {
            throw new PyOps.PyTypeError("expected " + n + " arguments, got " + a.size());
        }
    }
}
