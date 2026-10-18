package com.atomeng.erp.fcd.domain.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 시행일 기준(effective-dated) 룰셋 — Python {@code atomeng_common.rules.ruleset} 이식, <b>같은 YAML 포맷</b>
 * (재무제표 AI v1.0 §6.8: rule_id · title · severity · legal_basis · condition_expr · effect · message · review_required
 * + 탐지 확장: layer · weight · strength_expr · floor_score · evidence · effective_from/to · enabled · unverified · source).
 * legal_basis 가 빈 규칙·4-eyes 위반·승인 없는 ACTIVE 는 로드 단계에서 거부한다.
 */
public final class RuleSet {

    public static final class RuleSetError extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public RuleSetError(String m) {
            super(m);
        }
    }

    /** 규칙 적중 (Python RuleHit) */
    public record RuleHit(String ruleId, String layer, String kind, double strength, double weight, Integer floorScore,
                          String severity, String title, String message, List<String> legalBasis,
                          Map<String, Object> evidence, boolean reviewRequired) {
        public double ws() {
            return weight * strength;
        }
    }

    /** 규칙 정의 (Python Rule) */
    public static final class Rule {
        private final String ruleId;
        private final String title;
        private final String severity;
        private final List<String> legalBasis;
        private final String conditionExpr;
        private final String layer;
        private final double weight;
        private final String strengthExpr;
        private final Integer floorScore;
        private final Map<String, Object> effect;
        private final String message;
        private final List<String> evidence;
        private final boolean reviewRequired;
        private final LocalDate effectiveFrom;
        private final LocalDate effectiveTo;
        private final boolean enabled;
        private final boolean unverified;
        private final String source;
        private final PyExpr.Node cond;
        private final PyExpr.Node strength;

        Rule(String ruleId, String title, String severity, List<String> legalBasis, String conditionExpr, String layer,
             double weight, String strengthExpr, Integer floorScore, Map<String, Object> effect, String message,
             List<String> evidence, boolean reviewRequired, LocalDate effectiveFrom, LocalDate effectiveTo,
             boolean enabled, boolean unverified, String source) {
            if (!Set.of("hard", "soft", "flag").contains(severity)) {
                throw new RuleSetError(ruleId + ": severity must be hard|soft|flag");
            }
            if (legalBasis.isEmpty() || legalBasis.stream().anyMatch(x -> x == null || x.isBlank())) {
                throw new RuleSetError(ruleId + ": legal_basis must not be empty");
            }
            if (!(weight >= 0.0 && weight <= 1.0)) {
                throw new RuleSetError(ruleId + ": weight must be within [0,1]");
            }
            this.ruleId = ruleId;
            this.title = title;
            this.severity = severity;
            this.legalBasis = List.copyOf(legalBasis);
            this.conditionExpr = conditionExpr;
            this.layer = layer;
            this.weight = weight;
            this.strengthExpr = strengthExpr;
            this.floorScore = floorScore;
            this.effect = effect;
            this.message = message;
            this.evidence = List.copyOf(evidence);
            this.reviewRequired = reviewRequired;
            this.effectiveFrom = effectiveFrom;
            this.effectiveTo = effectiveTo;
            this.enabled = enabled;
            this.unverified = unverified;
            this.source = source;
            this.cond = PyExpr.compile(conditionExpr);
            this.strength = strengthExpr != null ? PyExpr.compile(strengthExpr) : null;
        }

        public String ruleId() {
            return ruleId;
        }

        public String layer() {
            return layer;
        }

        public double weight() {
            return weight;
        }

        public Integer floorScore() {
            return floorScore;
        }

        public Map<String, Object> effect() {
            return effect;
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean unverified() {
            return unverified;
        }

        public String source() {
            return source;
        }

        public String severity() {
            return severity;
        }

        public String title() {
            return title;
        }

        public String conditionExpr() {
            return conditionExpr;
        }

        public String kind() {
            Object k = effect.get("kind");
            return k == null ? "risk" : PyFormat.str(k);
        }

        public boolean activeOn(LocalDate d) {
            if (!enabled) {
                return false;
            }
            if (effectiveFrom != null && d.isBefore(effectiveFrom)) {
                return false;
            }
            return effectiveTo == null || d.isBefore(effectiveTo);
        }

        /** Python Rule.evaluate — 조건 거짓이면 null */
        public RuleHit evaluate(Map<String, Object> env, Double weightOverride) {
            if (!PyOps.truthy(cond.eval(env))) {
                return null;
            }
            double s = 1.0;
            if (strength != null) {
                Object v = strength.eval(env);
                if (v == null) {
                    return null;
                }
                s = PyOps.toFloat(v);
            }
            if (kind().equals("mitigation") && strength == null) {
                Object f = effect.get("factor");
                s = f != null ? PyOps.toFloat(f) : s;
            }
            double t = s < 1.0 ? s : 1.0;   // Python max(0.0, min(1.0, s)) — NaN 처리까지 같게
            s = t > 0.0 ? t : 0.0;
            if (s <= 0 && !kind().equals("flag")) {
                return null;
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            for (String path : evidence) {
                ev.put(path, resolve(env, path));
            }
            return new RuleHit(ruleId, layer, kind(), s, weightOverride == null ? weight : weightOverride, floorScore,
                    severity, title, formatMessage(message, env), legalBasis, ev, reviewRequired);
        }
    }

    private final String name;
    private final String version;
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveTo;
    private String status;
    private final String authoredBy;
    private String approvedBy;
    private final Map<String, Object> params;
    private final List<Rule> rules;
    private final List<Object> unverifiedNotes;
    private final String sha256;

    private RuleSet(String name, String version, LocalDate effectiveFrom, LocalDate effectiveTo, String status,
                    String authoredBy, String approvedBy, Map<String, Object> params, List<Rule> rules,
                    List<Object> unverifiedNotes, String sha256) {
        this.name = name;
        this.version = version;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.status = status;
        this.authoredBy = authoredBy;
        this.approvedBy = approvedBy;
        this.params = params;
        this.rules = rules;
        this.unverifiedNotes = unverifiedNotes;
        this.sha256 = sha256;
    }

    // ------------------------------------------------------------------ 로드

    public static RuleSet parse(String yaml, String defaultSource) {
        Yaml y = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object doc = y.load(yaml);
        if (!(doc instanceof Map<?, ?>)) {
            throw new RuleSetError("ruleset YAML must be a mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) PyOps.normalize(doc);
        return fromMap(d, defaultSource, sha256(yaml));
    }

    @SuppressWarnings("unchecked")
    static RuleSet fromMap(Map<String, Object> d, String defaultSource, String sha) {
        String rsSource = d.containsKey("source") ? PyFormat.str(d.get("source")) : defaultSource;
        List<Rule> rules = new ArrayList<>();
        for (Object o : listOf(d.get("rules"))) {
            Map<String, Object> rd = (Map<String, Object>) o;
            if (!rd.containsKey("rule_id") || !rd.containsKey("condition_expr")) {
                throw new RuleSetError("rule_id/condition_expr required");
            }
            String id = PyFormat.str(rd.get("rule_id"));
            Object sx = rd.get("strength_expr");
            Object floor = rd.get("floor_score");
            rules.add(new Rule(id, PyFormat.str(rd.getOrDefault("title", id)),
                    PyFormat.str(rd.getOrDefault("severity", "soft")),
                    listOf(rd.get("legal_basis")).stream().map(x -> x == null ? null : PyFormat.str(x)).toList(),
                    PyFormat.str(rd.get("condition_expr")), PyFormat.str(rd.getOrDefault("layer", "L1")),
                    PyOps.toFloat(rd.getOrDefault("weight", 0.5)), sx == null ? null : PyFormat.str(sx),
                    floor == null ? null : (int) PyOps.toInt(floor),
                    rd.get("effect") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(),
                    PyFormat.str(rd.getOrDefault("message", "")),
                    listOf(rd.get("evidence")).stream().map(PyFormat::str).toList(),
                    PyOps.truthy(rd.getOrDefault("review_required", false)),
                    date(rd.get("effective_from")), date(rd.get("effective_to")),
                    PyOps.truthy(rd.getOrDefault("enabled", true)), PyOps.truthy(rd.getOrDefault("unverified", false)),
                    rd.containsKey("source") ? PyFormat.str(rd.get("source")) : rsSource));
        }
        if (rules.stream().map(Rule::ruleId).distinct().count() != rules.size()) {
            throw new RuleSetError("duplicate rule_id in ruleset");
        }
        String status = PyFormat.str(d.getOrDefault("status", "DRAFT"));
        String authored = PyFormat.str(d.getOrDefault("authored_by", "unknown"));
        Object approvedRaw = d.get("approved_by");
        String approved = approvedRaw == null ? null : PyFormat.str(approvedRaw);
        if (approved != null && approved.equals(authored)) {
            throw new RuleSetError("4-eyes violation: approved_by must differ from authored_by");
        }
        if (status.equals("ACTIVE") && (approved == null || approved.isEmpty())) {
            throw new RuleSetError("ACTIVE ruleset requires approved_by");
        }
        if (!d.containsKey("version") || !d.containsKey("effective_from")) {
            throw new RuleSetError("version/effective_from required");
        }
        return new RuleSet(PyFormat.str(d.getOrDefault("ruleset", "rules")), PyFormat.str(d.get("version")),
                date(d.get("effective_from")), date(d.get("effective_to")), status, authored, approved,
                d.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), List.copyOf(rules),
                listOf(d.get("unverified")), sha);
    }

    private static List<Object> listOf(Object o) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        throw new RuleSetError("list expected: " + o);
    }

    private static LocalDate date(Object v) {
        if (v == null) {
            return null;
        }
        String s = PyFormat.str(v);
        if (s.isEmpty() || s.equals("null")) {
            return null;
        }
        return LocalDate.parse(s);
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 평가

    public boolean covers(LocalDate d) {
        return !d.isBefore(effectiveFrom) && (effectiveTo == null || d.isBefore(effectiveTo));
    }

    public Rule rule(String id) {
        return rules.stream().filter(r -> r.ruleId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(id));
    }

    /** Python RuleSet.evaluate — 층 필터 · 규칙별 시행일 · 가중치 대체 */
    public List<RuleHit> evaluate(Map<String, Object> env, LocalDate on, Set<String> layers,
                                  Map<String, Double> weightOverrides) {
        List<RuleHit> out = new ArrayList<>();
        for (Rule r : rules) {
            if (layers != null && !layers.contains(r.layer())) {
                continue;
            }
            if (!r.activeOn(on)) {
                continue;
            }
            RuleHit h = r.evaluate(env, weightOverrides == null ? null : weightOverrides.get(r.ruleId()));
            if (h != null) {
                out.add(h);
            }
        }
        return out;
    }

    public boolean usesUnverified(Collection<RuleHit> hits) {
        Set<String> ids = new java.util.HashSet<>();
        rules.stream().filter(Rule::unverified).forEach(r -> ids.add(r.ruleId()));
        return hits.stream().anyMatch(h -> ids.contains(h.ruleId()));
    }

    /** 4-eyes 승인(BR-FCD-009) — 작성자 본인 승인 금지 */
    public void approve(String approver) {
        if (approver.equals(authoredBy)) {
            throw new SecurityException("4-eyes: 작성자 본인은 승인할 수 없습니다");
        }
        this.approvedBy = approver;
        this.status = "ACTIVE";
    }

    /** DB 거버넌스 값(상태 · 작성자 · 승인자)으로 덮어쓴 사본 — YAML 의 값보다 DB 가 우선 */
    public RuleSet governed(String status, String authoredBy, String approvedBy) {
        if (approvedBy != null && approvedBy.equals(authoredBy)) {
            throw new RuleSetError("4-eyes violation: approved_by must differ from authored_by");
        }
        return new RuleSet(name, version, effectiveFrom, effectiveTo, status, authoredBy, approvedBy, params, rules,
                unverifiedNotes, sha256);
    }

    public void markDraft() {
        this.status = "DRAFT";
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }

    public String status() {
        return status;
    }

    public String authoredBy() {
        return authoredBy;
    }

    public String approvedBy() {
        return approvedBy;
    }

    public Map<String, Object> params() {
        return params;
    }

    public List<Rule> rules() {
        return rules;
    }

    public List<Object> unverifiedNotes() {
        return unverifiedNotes;
    }

    public String sha256() {
        return sha256;
    }

    // ------------------------------------------------------------------ 메시지

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.]*)(?::([^}]*))?\\}");

    static Object resolve(Map<String, Object> env, String dotted) {
        String[] parts = dotted.split("\\.");
        Object cur = env.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (cur == null) {
                return null;
            }
            cur = PyExpr.getAttr(cur, parts[i]);
        }
        return cur;
    }

    /** '{tx.amount:,.0f}원' 같은 점 경로 플레이스홀더 치환 (Python format_message) */
    public static String formatMessage(String template, Map<String, Object> env) {
        if (template == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = resolve(env, m.group(1));
            String spec = m.group(2);
            String rep;
            if (v == null) {
                rep = "-";
            } else if (spec == null || spec.isEmpty()) {
                rep = PyFormat.str(v);
            } else {
                try {
                    rep = PyFormat.format(v, spec);
                } catch (PyOps.PyValueError | PyOps.PyTypeError e) {
                    rep = PyFormat.str(v);
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
