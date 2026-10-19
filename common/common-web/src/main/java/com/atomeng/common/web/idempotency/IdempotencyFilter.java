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
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 80;

    private final IdempotencyStore store;
    private final CurrentActor currentActor;
    private final ProblemWriter problems;
    private final IdempotencyProperties props;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyFilter(IdempotencyStore store, CurrentActor currentActor, ProblemWriter problems,
                             IdempotencyProperties props, ObjectMapper objectMapper, Clock clock) {
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

        if (!acquire(req, res, actorId, key, requestHash)) {
            return; // 재반환·409·422 응답을 이미 썼다
        }

        var wrapped = new ContentCachingResponseWrapper(res);
        boolean stored = false;
        try {
            chain.doFilter(cached, wrapped);
            int status = wrapped.getStatus();
            if (status < 500) {
                store.complete(actorId, key, status, jsonOrNull(wrapped.getContentAsByteArray()));
                stored = true;
            }
        } finally {
            if (!stored) {
                store.release(actorId, key); // 5xx·예외: 저장하지 않음 → 재시도 시 재처리
            }
            wrapped.copyBodyToResponse();
        }
    }

    /** @return true = 이 요청이 처리 소유권을 얻음 */
    private boolean acquire(HttpServletRequest req, HttpServletResponse res, UUID actorId, String key,
                            String requestHash) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (store.tryBegin(actorId, key, requestHash)) {
                return true;
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
                    return true;
                }
                continue;
            }
            if (!e.requestHash().equals(requestHash)) {
                problems.write(req, res, CoreErrors.IDEMPOTENCY_KEY_REUSED,
                        "같은 Idempotency-Key 로 다른 요청을 보냈습니다");
                return false;
            }
            if (e.inProgress()) {
                problems.write(req, res, CoreErrors.IDEMPOTENCY_IN_PROGRESS, "동일 요청을 처리 중입니다");
                return false;
            }
            replay(res, e);
            return false;
        }
        problems.write(req, res, CoreErrors.IDEMPOTENCY_IN_PROGRESS, "동일 요청을 처리 중입니다");
        return false;
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
