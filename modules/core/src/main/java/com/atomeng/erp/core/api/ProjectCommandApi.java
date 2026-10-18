package com.atomeng.erp.core.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 프로젝트 생성 공개 포트 (01-IMPL Step 9 "core::api ProjectCommandApi.createFromContract()").
 * SAL 계약 승인 트랜잭션 안에서 동기 호출한다 — 승인 응답에 프로젝트 번호를 바로 돌려주기 위함(이벤트로 미루지 않음).
 * 호출자 트랜잭션에 참여하므로 실패하면 계약 상태 · outbox 와 함께 롤백된다.
 */
public interface ProjectCommandApi {

    /**
     * @param numberingYear 프로젝트 번호 채번 연도 — BR-SAL-008: 계약 승인(서명) 기준 연도 PRJ-{yyyy}-{seq4}
     * @param amountKrw     자사 지분 원화 금액(공동도급 지분 반영, A8-4)
     */
    record CreateFromContract(UUID contractId, String name, String serviceType, UUID clientId, LocalDate startDate,
                              LocalDate endDate, BigDecimal amountKrw, String currency, int numberingYear) {}

    record CreatedProject(UUID projectId, String projectNo) {}

    CreatedProject createFromContract(CreateFromContract cmd);
}
