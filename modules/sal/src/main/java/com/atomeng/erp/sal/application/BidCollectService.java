package com.atomeng.erp.sal.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.sal.adapter.out.persistence.SalJdbc;
import com.atomeng.erp.sal.api.SalEvents;
import com.atomeng.erp.sal.application.port.BidNoticeSourcePort;
import com.atomeng.erp.sal.application.port.BidNoticeSourcePort.NoticeItem;
import com.atomeng.erp.sal.domain.OpportunityStage;
import com.atomeng.erp.sal.domain.SalCodes;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 나라장터 공고 수집 (FR-SAL-001, BR-SAL-001·013, 01-IMPL Step 10).
 * <ul>
 *   <li>구간: 직전 <b>성공</b> NOTICE 실행의 range_to − 10분 ~ 현재(첫 실행은 최근 2일). 실패 실행은 기준이 되지 않으므로
 *       실패 구간은 다음 실행이 자동으로 다시 포함한다(백필, TC-SAL-004)</li>
 *   <li>collect_log 는 시작 · 종료를 각각 짧은 트랜잭션으로 기록 — 외부 API 호출은 DB 트랜잭션 밖, 반영은 한 트랜잭션(실패 시 전부 롤백)</li>
 *   <li>Upsert: (source, bid_no, bid_seq) ON CONFLICT DO UPDATE … WHERE raw_json 이 다를 때만(같은 응답 2회 = updated_at 불변, TC-SAL-001)</li>
 *   <li>정정공고(새 차수): 이전 차수 SUPERSEDED · superseded_by, 연결 기회 criteria_changed(TC-SAL-002) / 취소공고: 연결 기회 DROPPED(A8-3)</li>
 * </ul>
 * 다중 노드 중복 실행 방지는 프로세스 내 플래그만(설계의 Redis 락 · ShedLock 은 후속).
 */
@Service
public class BidCollectService {

    private static final Logger log = LoggerFactory.getLogger(BidCollectService.class);
    private static final int MAX_PAGES = 200;

    public record CollectResult(long logId, String jobType, OffsetDateTime from, OffsetDateTime to, boolean success,
                                int fetched, int upserted, int inserted, String error) {}

    private final SalJdbc sal;
    private final BidNoticeSourcePort source;
    private final OpportunityService opportunities;
    private final OutboxPublisher outbox;
    private final SalProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public BidCollectService(SalJdbc sal, BidNoticeSourcePort source, OpportunityService opportunities,
                             OutboxPublisher outbox, SalProperties props, TransactionTemplate tx, Clock clock) {
        this.sal = sal;
        this.source = source;
        this.opportunities = opportunities;
        this.outbox = outbox;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /** BR-SAL-013: 다음 정기 수집 구간의 시작 */
    public OffsetDateTime nextRangeFrom(OffsetDateTime now) {
        List<Timestamp> last = sal.jdbc().queryForList("""
                SELECT range_to FROM sal.collect_log WHERE job_type = 'NOTICE' AND success
                 ORDER BY id DESC LIMIT 1""", Timestamp.class);   // "직전" 성공 실행(가장 최근 실행)
        if (last.isEmpty()) {
            return now.minus(props.initialLookback());
        }
        return last.get(0).toInstant().atOffset(ZoneOffset.UTC).minus(props.collectOverlap());
    }

    /** 정기 실행(30분) — 구간은 collect_log 로 결정 */
    public CollectResult runScheduled() {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        OffsetDateTime from = nextRangeFrom(now);
        if (from.isAfter(now.minus(props.collectOverlap()))) {   // 시계 역행 · 짧은 간격 재실행 방어: 최소 겹침 구간은 조회
            from = now.minus(props.collectOverlap());
        }
        return collect("NOTICE", from, now);
    }

    /** 수동 백필(POST /bid-notices:collect) — BACKFILL 은 정기 구간 계산의 기준이 되지 않는다 */
    public CollectResult backfill(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || to.isBefore(from) || Duration.between(from, to).toDays() > 31) {
            throw new DomainException(SalCodes.INVALID, "백필 구간 오류(from ≤ to, 최대 31일)");
        }
        return collect("BACKFILL", from, to);
    }

    CollectResult collect(String jobType, OffsetDateTime from, OffsetDateTime to) {
        if (!running.compareAndSet(false, true)) {
            throw new DomainException(SalCodes.COLLECT_RUNNING, "공고 수집이 이미 실행 중입니다");
        }
        try {
            Long logId = tx.execute(s -> sal.jdbc().queryForObject("""
                    INSERT INTO sal.collect_log(job_type, range_from, range_to, started_at) VALUES (?, ?, ?, ?) RETURNING id""",
                    Long.class, jobType, SalJdbc.ts(from), SalJdbc.ts(to), Timestamp.from(clock.instant())));
            long id = logId == null ? -1 : logId;
            List<NoticeItem> items = new ArrayList<>();
            try {
                int pageNo = 1;
                BidNoticeSourcePort.Page page;
                do {
                    page = source.fetchNotices(from, to, pageNo++);
                    items.addAll(page.items());
                } while (page.hasNext() && pageNo <= MAX_PAGES);
                int[] counts = tx.execute(s -> apply(items));
                int upserted = counts == null ? 0 : counts[0];
                int inserted = counts == null ? 0 : counts[1];
                finish(id, true, items.size(), upserted, inserted, null);
                return new CollectResult(id, jobType, from, to, true, items.size(), upserted, inserted, null);
            } catch (RuntimeException e) {
                String err = e instanceof BidNoticeSourcePort.SourceUnavailable su ? su.reason() : e.toString();
                log.warn("sal 공고 수집 실패 {} {}~{}: {}", jobType, from, to, err);
                finish(id, false, items.size(), 0, 0, err);
                return new CollectResult(id, jobType, from, to, false, items.size(), 0, 0, err);
            }
        } finally {
            running.set(false);
        }
    }

    private void finish(long id, boolean ok, int fetched, int upserted, int inserted, String error) {
        tx.executeWithoutResult(s -> sal.jdbc().update("""
                UPDATE sal.collect_log SET finished_at = ?, success = ?, fetched_count = ?, upserted_count = ?,
                       new_count = ?, error = ? WHERE id = ?""", Timestamp.from(clock.instant()), ok, fetched, upserted,
                inserted, error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error, id));
    }

    /** @return [upserted(신규+변경), inserted(신규)] */
    private int[] apply(List<NoticeItem> items) {
        int upserted = 0;
        int inserted = 0;
        for (NoticeItem it : items) {
            List<Map<String, Object>> r = sal.jdbc().queryForList("""
                    INSERT INTO sal.bid_notice(id, source, bid_no, bid_seq, title, org_name, demand_org_name, contract_method,
                                               award_method, notice_kind, est_price, base_price, region_limit, notice_at,
                                               open_at, close_at, notice_url, raw_json, created_by, updated_by)
                    VALUES (?, 'G2B', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    ON CONFLICT (source, bid_no, bid_seq) DO UPDATE SET
                      title = EXCLUDED.title, org_name = EXCLUDED.org_name, demand_org_name = EXCLUDED.demand_org_name,
                      contract_method = EXCLUDED.contract_method, award_method = EXCLUDED.award_method,
                      notice_kind = EXCLUDED.notice_kind, est_price = EXCLUDED.est_price, base_price = EXCLUDED.base_price,
                      region_limit = EXCLUDED.region_limit, notice_at = EXCLUDED.notice_at, open_at = EXCLUDED.open_at,
                      close_at = EXCLUDED.close_at, notice_url = EXCLUDED.notice_url, raw_json = EXCLUDED.raw_json,
                      updated_at = now(), updated_by = EXCLUDED.updated_by, version = sal.bid_notice.version + 1
                    WHERE sal.bid_notice.raw_json IS DISTINCT FROM EXCLUDED.raw_json
                    RETURNING id, (xmax = 0) AS inserted""",
                    UuidV7.next(), it.bidNo(), it.bidSeq(), it.title(), it.orgName(), it.demandOrgName(),
                    it.contractMethod(), it.awardMethod(), it.noticeKind(), it.estPrice(), it.basePrice(),
                    it.regionLimit(), SalJdbc.ts(it.noticeAt()), SalJdbc.ts(it.openAt()), SalJdbc.ts(it.closeAt()),
                    it.noticeUrl(), it.raw().toString(), CurrentActor.SYSTEM, CurrentActor.SYSTEM);
            if (r.isEmpty()) {
                continue;   // 원문 동일 — 변경 없음
            }
            upserted++;
            UUID id = SalJdbc.uuid(r.get(0), "id");
            boolean isNew = Boolean.TRUE.equals(r.get(0).get("inserted"));
            if (isNew) {
                inserted++;
                // BR-SAL-001 정정공고: 같은 공고번호의 이전 차수 → SUPERSEDED, 연결 기회 "기준 변경" 플래그(A8-2)
                List<UUID> prev = sal.jdbc().queryForList("""
                        UPDATE sal.bid_notice SET status = 'SUPERSEDED', superseded_by = ?, updated_at = now(),
                               version = version + 1
                         WHERE source = 'G2B' AND bid_no = ? AND bid_seq < ? AND status <> 'SUPERSEDED' RETURNING id""",
                        UUID.class, id, it.bidNo(), it.bidSeq());
                for (UUID p : prev) {
                    sal.jdbc().update("""
                            UPDATE sal.opportunity SET criteria_changed = true, bid_notice_id = ?, version = version + 1,
                                   updated_at = now() WHERE bid_notice_id = ?""", id, p);
                }
                outbox.publish(new SalEvents.BidNoticeCollected(id, it.bidNo(), it.bidSeq(), null, true));
            }
            if (it.isCancellation()) {
                cancel(id, it.bidNo());
            }
        }
        return new int[] {upserted, inserted};
    }

    /** A8-3 취소공고 → 공고 CANCELLED, 연결 기회(진행 중) DROPPED(사유 공고취소) */
    private void cancel(UUID noticeId, String bidNo) {
        sal.jdbc().update("UPDATE sal.bid_notice SET status = 'CANCELLED', updated_at = now() WHERE id = ?", noticeId);
        List<UUID> opps = sal.jdbc().queryForList("""
                SELECT o.id FROM sal.opportunity o JOIN sal.bid_notice n ON n.id = o.bid_notice_id
                 WHERE n.source = 'G2B' AND n.bid_no = ? AND o.stage NOT IN ('WON','LOST','DROPPED','CONTRACTED')""",
                UUID.class, bidNo);
        for (UUID o : opps) {
            opportunities.moveIfAllowed(o, OpportunityStage.DROPPED, "공고취소");
        }
    }

    public List<Map<String, Object>> notices(String status, String q, int page, int size) {
        return sal.jdbc().queryForList("""
                SELECT id, bid_no AS "bidNo", bid_seq AS "bidSeq", title, org_name AS "orgName",
                       demand_org_name AS "demandOrgName", est_price AS "estPrice", close_at AS "closeAt",
                       open_at AS "openAt", notice_kind AS "noticeKind", status, fit_score AS "fitScore",
                       superseded_by AS "supersededBy", updated_at AS "updatedAt"
                  FROM sal.bid_notice
                 WHERE deleted_at IS NULL AND (?::text IS NULL OR status = ?::text)
                   AND (?::text IS NULL OR title_tsv @@ plainto_tsquery('simple', ?::text) OR title ILIKE '%' || ?::text || '%')
                 ORDER BY close_at DESC NULLS LAST, bid_no, bid_seq LIMIT ? OFFSET ?""",
                status, status, q, q, q, size, page * size);
    }

    public List<Map<String, Object>> collectLogs(int limit) {
        return sal.jdbc().queryForList("""
                SELECT id, job_type AS "jobType", range_from AS "rangeFrom", range_to AS "rangeTo", started_at AS "startedAt",
                       finished_at AS "finishedAt", success, fetched_count AS "fetched", upserted_count AS "upserted",
                       new_count AS "inserted", error FROM sal.collect_log ORDER BY id DESC LIMIT ?""", limit);
    }

    public static Map<String, Object> view(CollectResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logId", r.logId());
        m.put("jobType", r.jobType());
        m.put("from", r.from());
        m.put("to", r.to());
        m.put("success", r.success());
        m.put("fetched", r.fetched());
        m.put("upserted", r.upserted());
        m.put("inserted", r.inserted());
        m.put("error", r.error());
        return m;
    }
}
