package com.atomeng.common.web.idempotency;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.util.CanonicalJson;
import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.web.problem.ProblemWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Idempotency-Key 처리 (설계 9.4, 00-IMPL Step 5, TC-CORE-002).
 *
 * <table>
 *   <tr><th>조건</th><th>응답</th></tr>
 *   <tr><td>헤더 없음 (일반 클라이언트)</td><td>일반 처리</td></tr>
 *   <tr><td>헤더 없음 (필수 클라이언트 field-app)</td><td>428 CORE-428-001</td></tr>
 *   <tr><td>(actor, key) 없음</td><td>처리 후 요청 해시·응답 저장 (5xx 는 저장 안 함)</td></tr>
 *   <tr><td>같은 key·같은 hash·완료</td><td>저장 응답 재반환 + Idempotent-Replayed: true</td></tr>
 *   <tr><td>같은 key·같은 hash·처리 중</td><td>409 CORE-409-010</td></tr>
 *   <tr><td>같은 key·다른 hash</td><td>422 CORE-422-009</td></tr>
 *   <tr><td>다른 actor 의 같은 key</td><td>별개 네임스페이스 → 신규 처리</td></tr>
 * </table>
 * request_hash = SHA-256(method ‖ path?query ‖ 정규화 JSON 본문).
 *
 * <p><b>보안 검토 CMN-09</b>
 * <ul>
 *   <li>재반환 전 권한 재확인: 저장된 2xx/3xx 응답은 바로 돌려주지 않고 요청을 한 번 더 디스패치해 현재 주체로
 *       {@code @RequirePermission} 검사(역할 → 권한 → projectIdParam/scopeResolver 범위)를 다시 한다. 통과하면 업무 메서드는
 *       실행하지 않고({@link ReplayAuthorization} 구현이 막음) 저장 응답을 돌려주고, 거부되면 그 403/404 를 돌려준다.
 *       저장된 4xx(오류 본문)는 업무 자료가 없어 그대로 재반환한다. 재확인 구현(common-identity)이 없으면 예전처럼 바로 재반환</li>
 *   <li>바이너리(비 JSON) 응답(예: PTL {@code :download} PDF)은 저장하지 않는다 — 키를 풀어 재시도 때 다시 처리(예전에는
 *       상태코드만 저장돼 재반환이 빈 200 이었다)</li>
 * </ul>
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 80;

    /** 재반환 권한 재확인 중인 요청 표식(요청 속성) — 값은 {@link Boolean#TRUE} */
    public static final String REPLAY_PENDING_ATTR = IdempotencyFilter.class.getName() + ".REPLAY_PENDING";
    /** 현재 주체가 권한 재확인을 통과했다는 표식(요청 속성) */
    public static final String REPLAY_AUTHORIZED_ATTR = IdempotencyFilter.class.getName() + ".REPLAY_AUTHORIZED";

    /**
     * 재반환 권한 재확인 구현 표식(CMN-09). 구현체(common-identity)는 {@link #REPLAY_PENDING_ATTR} 요청에서 권한 검사를
     * 통과하면 업무 메서드를 실행하지 않고 {@link #markAuthorized} 를 호출해야 한다.
     */
    public interface ReplayAuthorization {}

    /** 권한 재확인 통과 표식 */
    public static void markAuthorized(jakarta.servlet.ServletRequest req) {
        req.setAttribute(REPLAY_AUTHORIZED_ATTR, Boolean.TRUE);
    }

    /** 이 요청이 재반환 권한 재확인 중인지 */
    public static boolean isReplayPending(jakarta.servlet.ServletRequest req) {
        return Boolean.TRUE.equals(req.getAttribute(REPLAY_PENDING_ATTR));
    }

    private final IdempotencyStore store;
    private final CurrentActor currentActor;
    private final ProblemWriter problems;
    private final IdempotencyProperties props;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean reauthorizeReplay;

    public IdempotencyFilter(IdempotencyStore store, CurrentActor currentActor, ProblemWriter problems,
                             IdempotencyProperties props, ObjectMapper objectMapper, Clock clock) {
        this(store, currentActor, problems, props, objectMapper, clock, false);
    }

    public IdempotencyFilter(IdempotencyStore store, CurrentActor currentActor, ProblemWriter problems,
                             IdempotencyProperties props, ObjectMapper objectMapper, Clock clock,
                             boolean reauthorizeReplay) {
        this.reauthorizeReplay = reauthorizeReplay;
        this.store = store;
        this.currentActor = currentActor;
        this.problems = problems;
        this.props = props;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader(HEADER);
        var actor = currentActor.current();

        if (key == null || key.isBlank()) {
            if (actor.isPresent() && actor.get().clientId() != null
                    && props.requiredClients().contains(actor.get().clientId())) {
                problems.write(req, res, CoreErrors.IDEMPOTENCY_KEY_REQUIRED,
                        "이 클라이언트는 POST 요청에 Idempotency-Key 헤더가 필수입니다");
                return;
            }
            chain.doFilter(req, res);
            return;
        }
        if (key.length() > MAX_KEY_LENGTH) {
            problems.write(req, res, CoreErrors.MALFORMED_REQUEST, "Idempotency-Key 는 80자 이하");
            return;
        }
        if (actor.isEmpty()) {
            problems.write(req, res, CoreErrors.UNAUTHENTICATED, "멱등 키는 인증된 사용자 단위로만 사용할 수 있습니다");
            return;
        }
        UUID actorId = actor.get().actorId();
        CachedBodyRequest cached;
        try {
            cached = new CachedBodyRequest(req, props.maxBodyBytes());
        } catch (CachedBodyRequest.TooLargeException e) {
            problems.write(req, res, CoreErrors.PAYLOAD_TOO_LARGE,
                    "멱등 키 요청 본문이 상한(%d바이트)을 넘습니다".formatted(props.maxBodyBytes()));
            return;
        }
        String target = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        String requestHash = Hashing.sha256Hex(
                req.getMethod().toUpperCase() + "\n" + target + "\n" + CanonicalJson.canonicalize(cached.body()));

        Acquired acq = acquire(req, res, actorId, key, requestHash);
        if (acq.replay() != null) {
            replayAfterReauthorization(cached, res, chain, acq.replay());
            return;
        }
        if (!acq.owner()) {
            return; // 409·422 응답을 이미 썼다
        }

        var wrapped = new ContentCachingResponseWrapper(res);
        boolean stored = false;
        try {
            chain.doFilter(cached, wrapped);
            int status = wrapped.getStatus();
            byte[] content = wrapped.getContentAsByteArray();
            String json = jsonOrNull(content);
            // CMN-09: 바이너리(비 JSON) 응답은 저장 · 재반환하지 않는다(빈 200 재반환 방지) → 키 해제, 재시도 시 재처리
            boolean binary = content.length > 0 && (json == null || !isJsonContentType(wrapped.getContentType()));
            if (status < 500 && !binary) {
                store.complete(actorId, key, status, json);
                stored = true;
            }
        } finally {
            if (!stored) {
                store.release(actorId, key); // 5xx·예외: 저장하지 않음 → 재시도 시 재처리
            }
            wrapped.copyBodyToResponse();
        }
    }

    /** owner = 처리 소유권, replay = 재반환할 저장 응답(권한 재확인 대상), 둘 다 아니면 응답을 이미 씀 */
    private record Acquired(boolean owner, IdempotencyStore.Entry replay) {
        static final Acquired OWNER = new Acquired(true, null);
        static final Acquired HANDLED = new Acquired(false, null);
    }

    /**
     * CMN-09: 저장 응답을 돌려주기 전에 현재 주체의 권한을 다시 확인한다. 2xx/3xx 는 요청을 재반환 확인 모드로 디스패치 —
     * 권한 검사를 통과하면(업무 메서드 미실행) 저장 응답, 거부되면 그 오류 응답을 그대로 보낸다.
     */
    private void replayAfterReauthorization(HttpServletRequest req, HttpServletResponse res, FilterChain chain,
                                            IdempotencyStore.Entry e) throws IOException, ServletException {
        if (!reauthorizeReplay || e.responseCode() >= 400) {
            replay(res, e);
            return;
        }
        var wrapped = new ContentCachingResponseWrapper(res);
        req.setAttribute(REPLAY_PENDING_ATTR, Boolean.TRUE);
        try {
            chain.doFilter(req, wrapped);
        } finally {
            req.removeAttribute(REPLAY_PENDING_ATTR);
        }
        if (Boolean.TRUE.equals(req.getAttribute(REPLAY_AUTHORIZED_ATTR))) {
            wrapped.reset();
            replay(wrapped, e);
        }
        wrapped.copyBodyToResponse();
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return true;   // 본문은 JSON 으로 파싱됨(형식 미지정)
        }
        try {
            MediaType mt = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(mt) || mt.getSubtype().endsWith("+json");
        } catch (org.springframework.util.InvalidMimeTypeException bad) {
            return false;
        }
    }

    private Acquired acquire(HttpServletRequest req, HttpServletResponse res, UUID actorId, String key,
                             String requestHash) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (store.tryBegin(actorId, key, requestHash)) {
                return Acquired.OWNER;
            }
            var existing = store.find(actorId, key);
            if (existing.isEmpty()) {
                continue; // 그 사이 해제됨 → 재시도
            }
            var e = existing.get();
            Instant now = clock.instant();
            boolean expired = e.createdAt().isBefore(now.minus(props.retention()));
            boolean staleLock = e.inProgress() && e.createdAt().isBefore(now.minus(props.lockTimeout()));
            if (expired || staleLock) {
                if (store.takeOver(e, requestHash)) {
                    return Acquired.OWNER;
                }
                continue;
            }
            if (!e.requestHash().equals(requestHash)) {
                problems.write(req, res, CoreErrors.IDEMPOTENCY_KEY_REUSED,
                        "같은 Idempotency-Key 로 다른 요청을 보냈습니다");
                return Acquired.HANDLED;
            }
            if (e.inProgress()) {
                problems.write(req, res, CoreErrors.IDEMPOTENCY_IN_PROGRESS, "동일 요청을 처리 중입니다");
                return Acquired.HANDLED;
            }
            return new Acquired(false, e);
        }
        problems.write(req, res, CoreErrors.IDEMPOTENCY_IN_PROGRESS, "동일 요청을 처리 중입니다");
        return Acquired.HANDLED;
    }

    private void replay(HttpServletResponse res, IdempotencyStore.Entry e) throws IOException {
        res.setStatus(e.responseCode());
        res.setHeader(REPLAYED_HEADER, "true");
        if (e.responseBody() != null) {
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            res.setContentType(e.responseCode() >= 400
                    ? MediaType.APPLICATION_PROBLEM_JSON_VALUE : MediaType.APPLICATION_JSON_VALUE);
            res.getOutputStream().write(e.responseBody().getBytes(StandardCharsets.UTF_8));
        }
    }

    private String jsonOrNull(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(body).toString();
        } catch (IOException notJson) {
            return null; // 비 JSON 본문은 저장하지 않는다(상태코드만 재반환)
        }
    }
}
