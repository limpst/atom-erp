# 보안 검토 보고서 — atom-erp (2026-10-19)

- 대상: `master` @ `e31e3f9` (12개 업무 모듈 + common/* + `services/aip-worker` + `deploy/keycloak`)
- 방법: 코드 정독(컨트롤러 전 엔드포인트 · 서비스 · 마이그레이션/권한 시드 · 설정) — 모듈군별 6개 갈래로 나눠 검토 후 교차 확인. 모든 지적은 코드로 확인했고, 추측은 넣지 않았습니다.
- 수정: 정책을 바꾸지 않는 명백한 버그만 고쳤습니다. 수정마다 회귀 시험을 붙였고 로컬 커밋만 했습니다(push 없음). 누가 승인할 수 있는지처럼 업무 정책에 해당하는 항목은 **정책 결정 필요**로 분류하고 권장안을 적었습니다.
- **후속 수정(같은 날, 2차):** 정책이 아닌 미결 항목 CMN-05 · AIP-03 · AIP-06 · FLD-02 · CORE-01 나머지 · CMN-06 과, 요청받은 운영 기동 가드(CMN-04 · CMN-07 의 가드 부분)를 고쳤습니다(§1 커밋 `853954a` ~ `444aad2`). 아래 표의 상태는 2차 수정 반영본입니다.
- 파일 경로는 저장소 루트 기준입니다. `…/` 는 `src/main/java/com/atomeng/erp/{mod}/` 를 줄인 표기입니다. 줄 번호는 검토 시점(`e31e3f9`) 기준입니다.

## 0. 요약

| 심각도 | 건수 | 수정 완료 | 일부 수정 | 정책 결정 필요 | 미수정(버그 · 후속) |
|---|---:|---:|---:|---:|---:|
| High | 12 | 8 | 0 | 4 | 0 |
| Medium | 41 | 23 | 3 | 15 | 0 |
| Low | 29 | 11 | 1 | 11 | 6 |
| Info | 8 | 1 | 0 | 2 | 5 |
| **합계** | **90** | **43** | **4** | **32** | **11** |

(1차 검토 직후: 수정 35 · 일부 3 · 정책 34 · 미수정 18. 2차에서 AIP-03 · CMN-05 · CMN-06 · FLD-02 · AIP-06 · CORE-01 · FLD-05 수정, CMN-04 정책→수정, CMN-07 정책→일부 수정(기동 가드만), AIP-07 미수정→일부 수정.)

집계 기준:
- 중복 표기(SAL-01 = CMN-01, PTL-02 = DEM-01)는 한 번만 셉니다.
- 수정 항목에 딸린 정책 하위 항목(AIP-01b · QAR-03b · QAR-04b · RPT-02b · FLD-02b)은 따로 세지 않습니다.
- "미수정" 열에는 조치가 필요 없는 Info 1건(RPT-09)이 포함됩니다.

**구조적 근본 원인 (CMN-05).** `RequirePermissionAspect` 는 `projectIdParam` 이 지정된 경우에만 PROJECT · CLIENT 범위를 검사합니다. SELF 범위는 아예 검사하지 않습니다(`common/common-identity/…/authz/RequirePermissionAspect.java:53`). 그래서 범위 검사를 서비스가 직접 하지 않은 엔드포인트는 **조용히 ALL 로 넓어집니다**. 이번 IDOR 대부분(DEM 일지, FCD 정산서, HRQ 리비전 · 직원 목록, AIP 무프로젝트 작업, FLD lock-check, CUSTOMER 의 RPT 접근)이 이 원인에서 나왔습니다.
→ **2차에서 수정(`853954a`)**: 범위가 ALL 이 아니면 `projectIdParam` · `scopeResolver` · `scopeCheckedInService = true` 중 하나가 있어야 하고, 없으면 403 `CORE-403-002`. `PermissionScopeDeclarationTest` 가 non-ALL 권한을 쓰는 모든 컨트롤러 메서드(실제 마이그레이션 시드 기준)를 전수 검사합니다.

**테넌트 격리(PTL RLS)는 양호합니다.**
- `set_config(…, true)` 와 `SET LOCAL ROLE` 은 트랜잭션 범위로만 적용됩니다. 트랜잭션 밖에서 호출하면 실패하는 쪽으로 닫혀 있습니다.
- 6개 테이블 모두 `FORCE ROW LEVEL SECURITY` 입니다.
- 설정값이 없으면 `nullif(current_setting(..,true),'')::uuid` 가 0행을 돌려줍니다.
- `ptl_customer` 역할은 `NOBYPASSRLS` · `NOINHERIT` 입니다.
- 오류 경로도 롤백으로 커넥션 풀에 상태가 남지 않습니다.

문제는 RLS 밖에 있는 **PTL 이 아닌 내부 API** 쪽이었습니다(PTL-01 · DEM-01).

## 1. 수정 커밋 (로컬 `master`, push 안 함)

| 커밋 | 내용 | 회귀 시험 |
|---|---|---|
| `c123573` | CMN-01 BigDecimal 지수 폭탄 차단(Jackson 모듈, \|scale\|≤50 · 유효숫자≤64 → 400) · CMN-02 멱등 키 본문 상한 51MB → 413 `CORE-413-001` · FCD-01 PAN HMAC 개발 키 · 짧은 키로 운영 모드 기동 실패 | `BoundedBigDecimalModuleTest`, `IdempotencyIntegrationTest`(+2), `CardServiceKeyGuardTest` |
| `0007e7f` | SAL-02 할인 반려 시 승인 흔적 삭제 · SAL-04 다른 기회의 견적 연결 거부 · SAL-07/EQP 페이징 int 오버플로 · EQP-05 ZPL 주입 | `SalIntegrationTest`(SEC-SAL-01~03), `EqpIntegrationTest`(SEC-EQP-01) |
| `422c89b` | FCD-02/03 정산서 · 정산 게이트 IDOR · FCD-04 룰셋 승인 단일 행 · FCD-05/06/15 평가기 DoS(`**` · 포맷 폭 · round 자릿수 · 중첩 깊이 · 식 길이) · FCD-09 errors.csv 수식 주입 · FCD-16 가맹점명 PAN 마스킹 · FCD-07 XLSX 엔트리 200MB · 20만 행 · 512열 상한 | `FcdSecurityRegressionTest`(6), `FcdIntegrationTest`(+1 · 보강), Python 골든 패리티 유지 |
| `d210d5a` | DEM-01 일지 조회 범위 · DEM-04 증거 첨부 소유자 · FLD-03 lock-check 범위 · FLD-04 opId 재생 행위자 한정 · FLD-01 미래 HLC 거부(24h) · AIP-01 승인 역할 위조/덮어쓰기 · AIP-05 무프로젝트 작업 요청자 한정 · AIP-04 멱등 키 충돌 시 타인 jobId 비노출 | `DemIntegrationTest`, `FldIntegrationTest`, `AipIntegrationTest` |
| `67da56a` | RPT-01 CLIENT 범위는 발행본만(초안 404) · RPT-02 다른 고객 시설물/건물 스냅샷 거부 · RPT-03 결함망도 PNG 크기 상한 · RPT-06 Content-Disposition 빌더 · QAR-02 재배정 QA 승인 SoD · QAR-03 수동 배정 검토자 프로필 확인 · QAR-04/05 승인 후 waiver · 코멘트 상태 변경 거부 | `RptSecurityRegressionTest`(2), `QarIntegrationTest.securityReviewRegressions`, `DefectMapPngBoundsTest` |
| `c71708e` | HRQ-04 타임시트 리비전 IDOR · HRQ-05 SELF 범위 직원 목록 · FAC-01 층 범위 무한 루프 · CORE-03 개인 고객 감사 PII 마스킹 · CORE-01(일부) 업로드 확정은 생성자만 · 골든 패스 PAN 스캔의 PNU 오탐 제거 | `HrqIntegrationTest`, `FacAclMergeNodeIntegrationTest`, `AuditPiiIntegrationTest`, `AttachmentIntegrationTest` |

| `853954a` | **CMN-05** aspect 실패하는 쪽으로 닫기 · `ScopeResolver` SPI(없는 자원 404) · AIP Job 조회/이벤트/취소 해석기 · 서비스 검사를 확인한 RPT · QAR · FLD · DEM · AIP · FCD · HRQ · PTL 108개 엔드포인트 `scopeCheckedInService` 명시 · SAL 영업기회 · EQP 장비(프로젝트 없는 자원) PM PROJECT 범위는 403(SAL-05 결정 대기) | `PermissionScopeDeclarationTest`, `PermissionFailClosedTest`(3) |
| `abbe1b7` | **CORE-01** 소유자 → 프로젝트 해석(`AttachmentOwnerResolver`: FLD 회차 · DEM 업무 패키지) + 투입/감리자 확인 · 크기 상한 50MB(신고 · 실제) · MIME 허용 목록 + 매직 바이트 · 실제 크기 기록 · 썸네일 1억 화소 상한 · DEM 증거 체인 범위 = 등록 패키지 / **FLD-02** 사진 50MB · 사용자당 열린 세션 20 · 만료 세션 410 · 만료 정리 스케줄러 · PATCH 본문 읽기 전 상한 · 조립 원본 매직 바이트 / **FLD-05** 다른 회차 defectId 거부 · 다른 회차 existingPhotoId 비노출 | `AttachmentIntegrationTest`(+2), `DemIntegrationTest`(보강), `FldIntegrationTest`(+2), `MediaSnifferAndThumbnailerTest`(2) |
| `0c1b4d1` | **AIP-03** 입력 첨부 소속 프로젝트 · sha 확인, 사진 작업 projectId · 첨부 필수, 워커 이미지는 서버 저장 원본(해시 재확인 · 15MB), 웹 `imageB64 · prompt · labels · model · findingModel` 422 / **AIP-06** HMAC-SHA256 서명(타임스탬프 ±300초 · nonce 재전송 차단 · `compare_digest`), 비밀 32바이트 미만 · 미설정이면 ERP 기동 실패 · 워커는 전부 401, 워커 주소는 루프백 + `allowed-hosts` 정확 일치만 · 리다이렉트 금지, OpenAPI 계약에 `AtomHmac` 기록 | `AipIntegrationTest`(+1), `AipWorkerContractTest`(+1 · 서명 검증), `AipWorkerLiveContractTest`(+1 실워커 401 · 재전송 · 변조), pytest `test_auth.py`(9) |
| `444aad2` | **CMN-06** JWT `aud` 검증(`atom.security.audiences`, 기본 `atom-erp-api`, OIDC 모드에서 비면 기동 실패) · 두 realm 에 audience 매퍼 · `deploy/keycloak/README.md`(cid/eid 속성 관리자 전용 요구) / **CMN-04** prod·production 프로필 + `atom.security.enabled=false` 기동 거부 / **CMN-07** prod·production 프로필 + tmpdir 증거 저장소 기동 거부 | `ProductionGuardsAndAudienceTest`(3), `EvidenceProdGuardTest`, `KeycloakRealmExportTest.audienceMapper` |

전체 빌드 결과는 §9 를 보세요.

## 2. 정책 결정 필요 (요약 · 권장안)

| ID | 심각도 | 결정할 것 | 권장안 |
|---|---|---|---|
| HRQ-01 | High | 타임시트 승인자(현재 PM 이면 **어떤 프로젝트든 · 본인 것도** 승인) | 해당 프로젝트 RESP(책임자)만, EXEC/ADMIN 은 사유와 함께 대리 승인, **본인 승인 금지** |
| HRQ-02 | High | ENG 가 승인된 본인 시간을 정정하면 바로 APPROVED | ENG 정정은 SUBMITTED 리비전으로 두고 재승인. PM 정정은 자기 프로젝트만 |
| PTL-01 | High | CUSTOMER 가 내부 `/api/v1/rpt` 로 발행본 원본 PDF(워터마크 · 다운로드 감사 없음)를 받을 수 있음 | 매트릭스에서 `ptl:*` 밖 CUSTOMER 권한(`rpt:report:read` · `core:project:read` · `dem:supervision-log:read` · `fac:facility:read` · `sal:estimate:read`)을 삭제하고, 보안 체인에서 PORTAL realm 을 `/api/v1/ptl/**` 로만 제한 |
| AIP-02 | High | CANARY 의 `trafficPct` 를 클라이언트가 100 까지 지정 → EXEC 승인 없이 전체 트래픽 | canary ≤ 25%. 그 이상은 EXEC 승인 필수 |
| AIP-01b | Medium | 같은 사람이 QA 와 EXEC 두 역할로 두 승인을 모두 채움 | `exec_approved_by ≠ approved_by` 강제 |
| SAL-03 | Medium | 수동 단계 이동으로 BID_APPROVED · WON 을 우회 · `sal:opportunity:approve` 미사용 · 견적 없는 계약 | →BID_APPROVED 는 `sal:opportunity:approve` 필요, →WON 은 견적 수락 전이로만, 기회 연결 계약은 `estimateId` 필수 |
| SAL-05 | Medium | PM 의 PROJECT 범위 `sal:opportunity:*` · `eqp:equipment:*` 가 사실상 ALL(자원에 프로젝트가 없음). **2차(CMN-05) 이후 이 12개 엔드포인트는 PM 에게 403**(판정할 프로젝트가 없어 실패하는 쪽으로 닫힘) — 결정 전까지 ALL 역할만 사용 | 읽기는 ALL 로 명시, PM 의 `opportunity:update` 삭제(시드 변경 후 `PermissionScopeDeclarationTest.FAIL_CLOSED_PENDING_POLICY` 에서 제거) |
| SAL-06 | Low | 다중 역할(SALES+EXEC) 본인 할인 · 계약 승인, EXEC 가 직접 발행하면 승인 기록 없음 | `approver ≠ created_by`. 한도는 발행자가 아니라 작성자 기준으로 |
| EQP-01 | Medium | 예약 취소는 누구나, PM 본인 예약을 본인이 승인, 아무 프로젝트로 예약 · 반출 | 취소는 신청자 또는 승인 역할, 승인자 ≠ 신청자 + 해당 프로젝트 PM, 예약 · 반출은 PROJECT 범위 |
| EQP-02 | Medium | 클라이언트가 보낸 `outAt`/`inAt` 로 검교정 판정과 사용일수(원가) 결정 → 소급 반출로 만료 장비 사용 | 서버 시각 기준, 오프라인 허용 오차 ≤ 72h, 미래 금지 |
| EQP-03 | Medium | 비행기록 `pilotId` 를 임의 지정할 수 있고, 조종 자격 확인은 스텁(항상 VALID) | 조종사 = 요청자(ADMIN 예외), 운영 프로필에서 스텁 금지 |
| EQP-04 | Low | QA 검교정 예외를 연쇄 · 소급 · 전역(projectId null)으로 걸 수 있음 | 장비 · 프로젝트별 활성 1건, 소급 금지, projectId 필수, 4-eyes |
| FCD-10 | Medium | 정산 라인에 아무 프로젝트 · WBS 를 지정 → CST 원가로 전기됨 | 투입(project_member) 확인 또는 PM 승인 |
| FCD-11 | Medium | 중복 MERGE 에 SoD 없음(본인 카드 거래를 FIN 이 병합해 없앰), 미결 케이스 거래도 병합 | 결정자 ≠ 카드 소지자, 미결 케이스 · 정산 라인이 있으면 MERGE 금지 |
| FCD-12 | Medium | 배치 롤백이 조사 중(OPEN · 소명요청 · 상향) 케이스의 거래를 soft-delete | KEEP_OPEN_CASE 처리 + 롤백 4-eyes(AUDITOR) |
| FCD-13 | Medium | 검토 실행 파라미터(minScore=101, k=0, policy 임의 문자열) 무제한 → 그 달 케이스 0건 | 설정 범위로 제한, 재정의는 AUDITOR 만 사유와 함께, policy 는 enum |
| FCD-14 | Low | 공용카드 SELF 배정을 과거로 소급 → 미배정 거래를 본인에게 귀속 | SELF 는 `start ≥ 오늘` · ACTIVE 카드만, 소급은 FIN 승인 |
| FCD-17 | Low | 케이스를 판정한 사람이 같은 대상자의 정산서를 나중에 승인할 수 있음 | 정산서 승인 시 해당 거래 케이스 판정자 제외 |
| FCD-18 | Info | 직원이 본인 거래의 룰 적중 상세(임계값)를 볼 수 있어 회피 학습 가능 | SELF 범위에는 요약만 노출 |
| QAR-01 | Medium | 최종 승인자가 보고서 작성자 · 섹션 편집자 · 등급 결정자일 수 있음(QA 만 제외) | 작성 · 편집 · 제출자 ≠ 최종 승인자(3자 분리) |
| QAR-03b | Medium | 모든 QA 가 아무 검토에나 재배정 · 자기 배정 가능 | `qar:assignment:update` 를 QA_LEAD 로 분리하거나 자기 배정 금지 |
| QAR-04b | Low | waiver QA 서명자가 해당 라운드 담당자가 아니어도 되고, PM 서명자가 작성자일 수 있음 | QA 측은 현재 담당자만, PM 측 ≠ 작성자 |
| RPT-07 | Low | 등급 결정 · 변경을 프로젝트 PM 누구나 할 수 있음(지정 책임기술자 · 자격 미확인) | 지정 책임기술자 + 자격 포트 확인 |
| RPT-02b | Low | 소유 고객이 없는 · FAC 에 없는 시설물은 스냅샷 검사를 통과 | 프로젝트에 연결된 시설물(cst.project_facility 등)만 허용 |
| DEM-02 | Medium | DEM 권한 시드가 전부 ALL → 아무 PM 이 다른 프로젝트 전이 · 종료 · 감리자 지정 · 일지 정정 · 리비전 승인 | PM/ENG 는 PROJECT 범위 + 서비스 검사(DemAccess 재사용), EXEC/QA/ADMIN 만 ALL |
| DEM-03 | Medium | 직무 분리: (a) PM 이 자신을 감리자로 지정, (b) 본인 일지 정정을 즉시 적용, (c) 같은 사람이 리비전 작성 · 검토요청 · 승인, (d) 리비전 작성자가 코멘트를 ACCEPT · CLOSE | 넷 다 `actor ≠ 대상/작성자`. 코멘트 종결은 코멘트 작성자 · 검토자만 |
| HRQ-06 | Medium | ADMIN 이 자기 자격증을 등록 · 승인하고 자기 등급을 변경 | 대상 직원 = 요청자면 거부(자격 승인은 BUG 성격이 강함) |
| HRQ-07 | Low | HRQ 월 마감과 재개를 같은 ADMIN 이 함 | CST 처럼 `hrq:month-close:reopen` 을 분리(SYSADMIN) |
| HRQ-03 | Medium | 타임시트를 투입되지 않은 프로젝트에 입력 가능, 하루 총시간 상한 없음 | 근무일 기준 투입 확인 + 하루 24h 상한 |
| CORE-02 | Medium | PM 이 자신을 RESP 로 지정하거나 투입 기간을 연장 | RESP 지정은 ALL 범위만, PM 은 PART/QA/SUPPORT 만, 본인 제외 |
| CST-01 | Low | 예산 결재에 maker-checker 없음, DRAFT 를 바로 승인 가능(현재 작성자는 SYSTEM 뿐) | `approver ≠ created_by/updated_by`, SUBMITTED 만 승인 |
| CST-02 | Low | 단가 소급(마감 기간 포함)을 한 사람이 처리 | `validFrom` 은 열린 기간만, 또는 결재 단계 |
| CMN-04 | Medium | ~~`atom.security.enabled=false` 를 운영에서 켤 수 있음~~ → **2차 수정**(`prod`·`production` 프로필이면 기동 거부). 남은 결정: 운영 배포가 이 프로필 이름을 반드시 쓰도록 배포 절차에 명시 | 배포 스크립트에서 `SPRING_PROFILES_ACTIVE=prod` 고정 |
| CMN-07 | Medium | 증거 저장소 기본값이 `${java.io.tmpdir}`, WORM 은 모사 — **2차: 운영 프로필에서 tmpdir(미설정 포함) 기동 거부는 수정**, 남은 것은 실제 WORM 저장소 선택 | S3/MinIO Object Lock 어댑터 |
| CMN-09 | Low | 멱등 재반환이 권한 재검사 없이 24h 동안 저장 응답을 반환 | 재반환 전 권한 재확인, 또는 상태 · 자원 id 만 저장 |
| CMN-14 | Low | outbox 한 구독자가 실패하면 같은 이벤트의 모든 구독자가 함께 재시도 · DLQ | 구독자별 전달 · 재시도(후속 설계) |
| PTL-03 | Low | 다운로드마다 동기 워터마크(메모리 전량) — 반복 다운로드로 DoS | `MemoryUsageSetting` 임시파일 + 고객사별 속도 제한 또는 비동기(202) |
| FLD-02b | Medium | 업로드 수치 한도(최대 크기 · 세션 수) — 2차에서 **권장 예시값을 기본값으로 넣고 설정으로 뺐습니다**(`atom.fld.upload.max-file-bytes` 50MB · `max-open-sessions-per-user` 20 · `session-ttl` 7일, `atom.attachment.max-bytes` 50MB). 수치 확정만 남음 | 현장 기기 최대 원본 크기 확인 후 확정 |
| AIP-08 | Info | 라벨 판정자 ≠ 라벨러(라벨 제출 API 가 생기면) | 생길 때 강제 |

## 3. 공통 · 플랫폼 (CMN)

| ID | 심각도 | 위치 | 시나리오 | 조치 | 상태 |
|---|---|---|---|---|---|
| CMN-01 | High | 모든 요청 본문 BigDecimal: SAL `FeeRuleSet.java:46,56`, `FeeEngine.java:79-94`, `ContractService.java:97,102`, `QualificationScorer.java:97` 등 | SALES 가 `{"unitPrice":"1e999999999"}` 를 보내면 `@PositiveOrZero` 를 통과하고, `setScale(0)` 이 10^999,999,999 자리 정수를 만들어 CPU 를 수 분 점유하거나 OOM | `common-web/…/json/BoundedBigDecimalModule` — 역직렬화에서 \|scale\|≤50 · 유효숫자≤64 밖이면 400 | **수정** `c123573` |
| CMN-02 | Medium | `common-web/…/idempotency/CachedBodyRequest.java:20` | 멱등 키를 붙인 POST 본문을 `readAllBytes()` 로 무제한 적재 → 토큰만 있으면(CUSTOMER 포함) 수 GB 본문으로 힙 고갈 | `atom.idempotency.max-body-bytes`(기본 51MB = multipart 상한) 초과 시 413 `CORE-413-001`. Content-Length 와 실제 읽은 양 모두 검사 | **수정** `c123573` |
| CMN-05 | Medium(구조) | `RequirePermissionAspect.java:53` | `projectIdParam` 이 없거나 SELF 범위면 바로 통과 → 서비스가 직접 검사하지 않으면 PROJECT/CLIENT/SELF 권한이 ALL 로 넓어짐(이번 IDOR 들의 근본 원인) | 실패하는 쪽으로 닫기 + `ScopeResolver` SPI + `scopeCheckedInService` 명시 + 전수 검사 시험 | **수정** `853954a` |
| CMN-04 | Medium | `IdentityAutoConfiguration.java:115-124`, `application.yml:155-162` | `ATOM_SECURITY_ENABLED=false` 만으로 `X-Roles: SYSADMIN` 헤더 위조 인증이 켜짐. 막는 장치 없음 | 운영 프로필 기동 가드(`ProductionGuards`): `prod`·`production` 프로필 + `atom.security.enabled=false` 면 기동 실패 | **수정** `444aad2` |
| CMN-06 | Medium | `deploy/keycloak/atom-portal-realm.json:30`, `IdentityAutoConfiguration.java:92` | 포털 `cid` 클레임이 사용자 속성 `client_id` 에서 매핑됨. 사용자가 그 속성을 편집할 수 있으면 고객사를 바꿀 수 있음. JWT `aud`/`azp` 검증 없음. 운영 realm 에 localhost redirect URI 가 있음 | `AudienceValidator`(aud ∋ `atom-erp-api`, OIDC 모드 필수) + realm audience 매퍼 + `deploy/keycloak/README.md` 에 `client_id`/`employee_id` 관리자 전용 User Profile 요구 · 배포 확인 절차. realm 의 User Profile 적용과 localhost redirect 제거는 운영 realm 에서 수행(문서화) | **수정** `444aad2` |
| CMN-07 | Medium | `common-evidence/…/EvidenceAutoConfiguration.java:19` | 운영에서도 증거 · 발행본이 tmp 에 저장될 수 있고, OS 정리 · 로컬 사용자가 삭제 · 변경 가능 | 운영 프로필 + tmpdir(미설정 기본값 포함) 증거 저장소 기동 거부 수정. Object Lock 어댑터는 정책(§2) | **일부 수정** `444aad2` |
| CMN-08 | Low | `application.yml:144`, `IdentityAutoConfiguration.java:100-102` | `/actuator/metrics` · `prometheus` 를 인증된 누구나(CUSTOMER 포함) 조회 | health 외 `/actuator/**` 는 SYSADMIN 또는 별도 관리 포트 | 미수정 |
| CMN-09 | Low | `IdempotencyFilter.java:146` | 권한이 회수된 사용자도 24h 안에는 저장 응답(PII 포함 가능)을 재반환받음. PTL `:download` 는 비 JSON 이라 재반환하면 빈 200(기능 버그) | 정책(§2) + 바이너리 응답 엔드포인트 제외 | 정책 |
| CMN-10 | Low | `common-events/…/OutboxRelay.java:183-196,135` | `map()` 이 점유 전에 예외(잘못된 headers.actorId)를 내면 그 행이 매번 다시 선택되어 outbox 전체가 정지 | 행 단위로 매핑 오류를 잡아 id 로 `outbox_dead` 로 이동 | 미수정(현재는 내부 발행만이라 발생 경로 없음) |
| CMN-11 | Low | `ptl/…/PortalReportService.java:96`, `GlobalProblemHandler.java:57`, `OutboxRelay.java:145,150` | 고객 응답에 저장소 예외 메시지(`object not found: bucket/key`) 노출, 로그에 PG 제약 상세 `Key (col)=(value)` | 고객에게는 일반 메시지, 로그에는 SQLState · 제약명만 | 미수정 |
| CMN-12 | Info | `LocalFsObjectStorage.java:52,103,114` | 버킷 `.` 이 SAFE_KEY 를 통과해 `./published` 로 WORM 검사를 우회할 수 있음(버킷은 현재 코드 상수라 악용 경로 없음) | 버킷 허용 목록 | 미수정 |
| CMN-13 | Info | `application.yml:7`, `deploy/keycloak/*.json` | DB 비밀번호 기본값 `atom_app_local`. Keycloak 시크릿은 `${…}` 자리표시자(하드코딩 없음) | 운영은 기본값을 제거해 미설정 시 기동 실패, realm import 때 자리표시자 치환 확인 | 미수정 |
| CMN-14 | Low | `OutboxRelay.java:101-165` | 구독자 하나가 실패하면 같은 트랜잭션의 다른 구독자도 롤백되고, 5회 후 모두 DLQ(중복 제거 자체는 `processed_event` PK 로 정확함) | 정책(§2) | 정책 |
| CMN-15 | Info | `GoldenPathScenarioTest.java:593` | 감사 로그 PAN 스캔이 다른 시험 클래스의 합성 PNU(19자리, Luhn 통과 확률 10%)를 오탐 → 실행 순서에 따라 빌드 실패 | 스캔 전에 `"pnu": "<19자리>"` 를 치환 | **수정** `c71708e` |

**확인 결과 양호**
- 멱등 키는 (actor, key) 네임스페이스로 분리되고, 요청 해시는 method · path?query · 정규화 JSON 입니다.
- outbox 점유는 `FOR UPDATE SKIP LOCKED` 이고, 소비 중복 제거는 `processed_event` PK(consumer, event_id) 를 같은 트랜잭션(MANDATORY)에서 씁니다.
- `GlobalProblemHandler` 는 스택 · SQL 을 응답에 넣지 않습니다.
- CORS 설정이 없어 기본 거부입니다. springdoc 이 없어 `/v3/api-docs` permitAll 은 실효가 없습니다.
- `JwtAuthConverter` 는 realm 이 다른 역할을 제거하고, issuer 는 허용 목록으로만 받습니다.
- LocalFs 키 경로 이탈은 정규식 + `..` + `startsWith(root)` 로 막혀 있습니다.
- tools · db · services 소스에 하드코딩된 비밀은 없습니다(.venv 제외).

## 4. 고객 포털 · 테넌트 격리 (PTL)

| ID | 심각도 | 위치 | 시나리오 | 조치 | 상태 |
|---|---|---|---|---|---|
| PTL-01 | High | `core/…/V0002__core_role_permission_matrix.sql:125` (`CUSTOMER rpt:report:read CLIENT`), `IdentityAutoConfiguration.java:102`(`anyRequest().authenticated()`), `RptController.java:137-257`, `RenderService.java:186-194` | 고객이 포털에서 받은 `revisionId` 로 `GET /api/v1/rpt/revisions/{id}/files/report.pdf` 를 호출 → **QA 승인 전 초안**, 등급 변경 사유 · 결정자, 편집자 직원 id, 그리고 워터마크 · 다운로드 감사 없는 발행본 원본 | (1) 초안 차단: CLIENT 범위는 PUBLISHED/DELIVERED/SUPERSEDED 리비전만, 나머지 404 — **수정** `67da56a`(RPT-01). (2) 발행본 원본을 내부 API 로 받는 경로는 매트릭스 변경이 필요해 정책(§2) | 정책 (초안 노출은 RPT-01 로 수정) |
| PTL-02 | High | `V0002…sql:129` (`CUSTOMER dem:supervision-log:read CLIENT`), `DemController.java:246-250`, `DailyLogService.java:365,421` | 포털 사용자가 다른 고객의 감리일지 id 로 `GET /api/v1/dem/daily-logs/{id}` → 일지 · 점검표 · 사진 id · 정정 이력 전체 | DEM-01 로 수정(CLIENT 범위 = 프로젝트 발주 고객만) | **수정** `d210d5a` (DEM-01 과 같은 건, 집계 제외) |
| PTL-03 | Low | `ptl/…/PdfWatermarker.java:45` | 다운로드마다 PDF 전체를 메모리에 올려 동기 처리 → 반복 호출로 CPU · 메모리 소진(원본은 신뢰할 수 있는 내부 렌더) | 정책(§2) | 정책 |

**확인 결과 양호**
- `PortalRls.java:34-59` 는 트랜잭션 밖에서 실패하는 쪽으로 닫혀 있고, 중첩을 막고, `finally` 에서 role 을 리셋합니다.
- 모든 포털 읽기 · 쓰기와 리스너가 `@Transactional` 안의 `asCustomer` 를 거치고, 다른 모듈 API 호출은 RLS 블록 밖에서 합니다.
- 다른 고객 자원은 404 를 돌려주고 DENIED 를 REQUIRES_NEW 트랜잭션으로 기록합니다.
- 다운로드 파일명 · 워터마크에는 UUID 와 시각만 들어가고, 워터마크 전에 원본 해시를 재검증합니다.

## 5. CORE · FAC · HRQ · CST

| ID | 심각도 | 위치 | 시나리오 | 조치 | 상태 |
|---|---|---|---|---|---|
| HRQ-01 | High | `hrq/…/adapter/in/web/HrqController.java:204-208`, `TimesheetService.java:101-124`, 시드 `V0010` (`hrq:timesheet:approve` PM=ALL) | **(알려진 건)** 프로젝트와 무관한 PM 이 아무 직원의 타임시트를 승인하고, PM+ENG 를 함께 가진 사람은 본인 것을 승인 → `hrq.timesheet.confirmed` 로 CST 인건비가 전기됨 | 정책(§2) | 정책 |
| HRQ-02 | High | `HrqController.java:211-225`, `TimesheetService.java:128-178`(:166 바로 APPROVED) | ENG 가 승인된 4h 를 24h 로 정정하면 재승인 없이 CST 역분개 · 재전기 | 정책(§2) | 정책 |
| HRQ-03 | Medium | `TimesheetService.java:72-98` | 투입되지 않은 프로젝트에 시간을 입력(FK 만 확인), 하루 합계 상한 없음 → HRQ-01 과 결합하면 남의 프로젝트 원가 증가 | 정책(§2) | 정책 |
| HRQ-04 | Medium | `HrqController.java:235-239` | `hrq:timesheet:read` 가 SELF 범위인 ENG · LABELER 가 `timesheetKey`(CST 원장 sourceId 로 노출됨)로 남의 근무 이력을 열람 | 행을 읽은 뒤 `requireSelfOr(employeeId, EXEC, PM, ADMIN)` | **수정** `c71708e` |
| HRQ-05 | Medium | `HrqController.java:118-122` | SELF 범위 ENG · LABELER 가 전 직원 이름 · 사번 · 고용형태 · 입사일 · 퇴사(예정)일을 조회 | SELF 범위면 본인 행만 | **수정** `c71708e` |
| FAC-01 | Medium | `fac/…/LocationNodeController.java:36`, `LocationNodeService.java:89-91` | `floorTo=2147483647` → `lvl++` 오버플로로 무한 루프 + 반복마다 DB 조회 · 메모리 적재 → JVM 전체 중단 | 층 -20..300, 노드 약 20만 상한 → 422 `FAC-422-003` | **수정** `c71708e` |
| CORE-01 | Medium | `core/…/AttachmentService.java:83-155`, `AttachmentController.java:27-36`, `common-evidence/…/Thumbnailer.java:23` | (a) `ownerModule/Type/Id` · `chainScope` 미확인 → 다른 프로젝트 증거 해시 체인에 가짜 증거를 덧붙임, (b) 크기 상한 · 실제 크기 비교 없음, (c) MIME 은 클라이언트 신고값, 썸네일 `ImageIO.read` 픽셀 상한 없음(압축 폭탄), (d) 다른 사람이 업로드를 complete | (a) 소유자 해석기(FLD 회차 · DEM 패키지 → 프로젝트) + 투입 인원/활성 감리자만 422 `CORE-422-013` (b) 신고 · 실제 크기 50MB 상한 413, 실제 크기 기록(신고값 일치 강제는 계약 변경이라 안 함) (c) MIME 허용 목록 + :complete 매직 바이트 422 `CORE-422-014`, 썸네일 디코드 전 1억 화소 상한 · 서브샘플링 (d) 생성자만 complete | **수정** `c71708e` · `abbe1b7` |
| CORE-02 | Medium | `ProjectController.java:77-86`, `ProjectService.java:105-121` | PART 로 투입된 PM 이 자기를 RESP 로 지정(pm_id 변경)하거나 기간을 연장 | 정책(§2) | 정책 |
| HRQ-06 | Medium | `CredentialService.java:58-73`, `EmployeeService.changeGrade` | ADMIN 이 본인 자격증 등록 · 승인, 본인 등급 상향(노임단가 · 등록요건에 영향) | 정책(§2) | 정책 |
| CORE-03 | Low | `core/…/ClientService.java:61-69` | 개인(INDIVIDUAL) 고객의 이름 · 사업자번호 · 주소가 감사 스냅샷에 마스킹 없이 저장 | `PiiMasking` 적용 | **수정** `c71708e` |
| HRQ-07 | Low | `HrqController.java:242-253` | 월 마감과 재개를 같은 ADMIN 이 함 | 정책(§2) | 정책 |
| CST-01 | Low | `cst/…/BudgetService.java:70-103`, `BudgetApproval.java:12` | 예산 결재에 maker-checker 없음(현재는 SYSTEM 만 작성해 악용 경로 없음) | 정책(§2) | 정책 |
| CST-02 | Low | `RateService.java:44-69` | 단가를 마감 기간으로 소급 | 정책(§2) | 정책 |

**확인 결과 양호**
- 네 컨트롤러 56개 엔드포인트 모두 `@RequirePermission` 이 있습니다.
- PROJECT 범위 권한은 모두 `projectIdParam` 을 쓰고, 경로 id 의 프로젝트 소속도 확인합니다(예산 · 진척 · 노드).
- `saveWeek` · `week` · `detail` · `addCredential` 의 SELF 검사가 정상입니다.
- SQL 은 전부 바인딩입니다. ltree 라벨은 정제한 뒤 `?::ltree` 로 넘깁니다.
- 공공 API(Juso · 건축물대장)는 base URL 을 설정에서만 받고, 키는 환경변수에서 읽으며 로그에 남기지 않습니다.
- CST 소비자 중복 제거와 마감 · 재개 분리(ADMIN/SYSADMIN)가 정상입니다.

## 6. FCD (법인카드)

| ID | 심각도 | 위치 | 시나리오 | 조치 | 상태 |
|---|---|---|---|---|---|
| FCD-01 | High | `fcd/…/application/CardService.java:42` (`${atom.fcd.token-hmac.v1:dev-only-not-a-secret-v1}`) | 운영에서 키 주입을 빠뜨리면 소스에 공개된 키가 조용히 쓰임. `card` 에 앞6 · 뒤4 가 있으므로 DB · 백업을 읽을 수 있는 사람이 가운데 약 10^5 후보(Luhn 적용 후)를 대입해 PAN 전체를 복원 | 운영 모드(`atom.security.enabled=true`)에서 개발 키 · 미설정 · 32바이트 미만이면 기동 실패. **운영 배포 시 `ATOM_FCD_TOKEN_HMAC_V1` 등으로 키 주입 필수** | **수정** `c123573` |
| FCD-02 | High | `FcdController.java:368-372` → `ExpenseService.java:232-239`, 시드 `V0034:24-25` | 직원 누구나 UUID 로 다른 직원의 정산서(라인 · 금액 · 프로젝트 · 승인자)를 조회 | 소유자 또는 `fcd:expense:approve/post` 보유자만, 아니면 403 `FCD-403-003`, 없는 id 는 404 | **수정** `422c89b` |
| FCD-03 | Medium | `FcdController.java:374-379` | SELF 범위로 다른 직원의 정산 게이트(차단 케이스 번호 = 부정사용 조사 중 여부)를 조회 | `selfOnly` 적용 | **수정** `422c89b` |
| FCD-04 | Medium | `RuleSetService.java:135,147`, `V0033__fcd_schema.sql:259` | 승인이 `version` 으로만 행을 찾음(유일키는 name+version). 공격자가 같은 버전 · 다른 이름의 초안을 올려 두면 정상 초안 승인 때 함께 ACTIVE 가 됨(4-eyes 우회) | 대상 행 id 로 1행만 갱신, 모호하면 409 `FCD-409-003`(`?name=` 선택) | **수정** `422c89b` |
| FCD-05 | Medium | `domain/rules/PyOps.java:412-419` | 룰식 `1 ** 9223372036854775807` 이 지수만큼 반복(초안은 컴파일만 해서 검증 통과) → 승인 후 채점이 멈추고, 가져오기 advisory lock 으로 모든 수입이 정지 | \|밑\|≤1 빠른 경로, 나머지는 기존 오버플로 오류 | **수정** `422c89b` |
| FCD-06 | Medium | `PyFormat.java:169,171,357`, `PyOps.java:473`, `PyExpr.java:788` | `{tx.amount:>999999999}` 이 적중마다 1GB 문자열, `round(0.5, 999999999)` 가 10^n 계산 | 폭 · 정밀도 > 1000 은 `PyValueError`, round 는 Python 과 같은 경계(>323 → x, <-308 → 0) | **수정** `422c89b` |
| FCD-07 | Medium | `domain/ingest/RowSource.java:265-293` | 50MB XLSX 가 수 GB 로 풀림(`setMinInflateRatio(0.01)` 는 POI 기본값, MaxEntrySize 4GB), 행 수 무제한 → OOM | 엔트리 200MB, 모든 형식 20만 행 · 512열 상한(`E-TOO-MANY-ROWS/COLS`) | **수정** `422c89b` |
| FCD-08 | Low | `RowSource.java:384-394` | HTML 위장 XLS 의 `(?is)<tr[^>]*>(.*?)</tr>` 가 닫히지 않은 `<tr>` 마다 끝까지 재탐색 → O(n²)(FIN 전용) | indexOf 스캐너 또는 jsoup | 미수정 |
| FCD-09 | Low | `domain/ingest/FileParser.java:282-300` | 카드사 파일의 가맹점 텍스트 `=HYPERLINK(...)` 이 errors.csv 에 그대로 → FIN 이 Excel 로 열면 실행 | `= + - @ TAB CR` 로 시작하면 `'` 접두 | **수정** `422c89b` |
| FCD-10 · 11 · 12 · 13 · 14 · 17 · 18 | Medium~Info | `ExpenseService.java:98-102`, `ImportService.java:295-296,346-350,405-413`, `ReviewQueueService.java:44-46`, `CardService.java:340-365`, `CaseService.java:197-212`, `FcdController.java:276-279` | §2 참조 | 정책 | 정책 |
| FCD-15 | Low | `PyExpr.java`(재귀 하강) | `((((…` 1만 중첩 → `StackOverflowError`(Error 라 `catch (RuntimeException)` 를 지나쳐 500) | 깊이 200 · 식 4000자 상한 → 잘못된 룰셋 처리 | **수정** `422c89b` |
| FCD-16 | Low | `FileParser.java:264` | `merchantName` 은 마스킹하지 않아 자유 텍스트 속 PAN 이 txn · merchant · staging 에 저장됨 | `maskPans` 적용(approval_no 는 8자리 자연키라 제외) | **수정** `422c89b` |

**확인 결과 양호**
- **PyExpr 샌드박스.** 직접 만든 파서이고 리플렉션 · SpEL · ScriptEngine 이 없습니다. 함수는 화이트리스트, 속성 접근은 Map 에만 허용하고 `_` 이름은 막습니다. 정규식 함수가 없고, 문자열 · 리스트 반복도 막혀 있습니다.
- **YAML.** SnakeYAML 은 `SafeConstructor` 이고, 2.x 기본값으로 별칭 50 · 중첩 50 · 3MB 상한이 걸립니다.
- **XXE.** POI 의 강화된 `XMLHelper` 를 씁니다.
- **4-eyes.** 정산서 본인 승인 금지, 룰셋 4-eyes(코드 + DB CHECK), 케이스 판정자가 대상자 · 이전 승인자가 아닐 것 + AUDITOR/EXEC 역할 조건이 모두 정상입니다.
- **PAN.** `toString` 마스킹, char[] 제로화, 오류 · 메시지 마스킹이 되어 있고, PAN 컬럼은 없습니다.
- **웹훅.** FCD 로 들어오는 웹훅은 존재하지 않습니다(외부 FDS 는 나가는 호출만). 따라서 타이밍 안전 비교 대상이 없습니다.
- **소비자.** `@IdempotentListener` 로 중복 제거됩니다.

## 7. SAL · EQP

| ID | 심각도 | 위치 | 시나리오 | 조치 | 상태 |
|---|---|---|---|---|---|
| SAL-01 | High | (= CMN-01) `/fee-calculations` · `/contracts` · `/simulations` | 지수 폭탄 | CMN-01 | **수정** (집계 제외) |
| SAL-02 | Medium | `sal/…/EstimateService.java:221-224` | EXEC A 가 승인한 뒤에도 상태가 PENDING_APPROVAL 이라 EXEC B 가 반려(DRAFT)할 수 있는데, `approved_by` 가 남음 → SALES 가 `:issue` 하면 반려된 할인으로 ISSUED | 반려 시 `approved_by/approved_at` 을 NULL 로(문서 주석과 일치) | **수정** `0007e7f` |
| SAL-03 | Medium | `OpportunityStage.java:17,21`, `OpportunityService.java:116`, `ContractService.java:80` | 수동 이동으로 BID_APPROVED · WON 을 만들고 견적 없이 계약 → BR-SAL-006 할인 통제를 우회 | 정책(§2) | 정책 |
| SAL-04 | Medium | `ContractService.java:105-115` | ACCEPTED 상태만 확인 → 다른 기회의 수락 견적을 연결해 1% 금액 검사를 통과시키고 그 라인이 `ContractSigned` 로 흘러감 | 견적의 opportunity 와 계약의 opportunity 가 다르면 422 `SAL-422-001` | **수정** `0007e7f` |
| SAL-05 | Medium | `V0002…sql:38-39,44-46` | PM 의 PROJECT 범위가 실제로는 ALL | 정책(§2) | 정책 |
| SAL-06 | Low | `EstimateService.java:170,199-219`, `ContractService.java:148` | 다중 역할 본인 승인 | 정책(§2) | 정책 |
| SAL-07 | Low | `OpportunityService.java:101`, `BidCollectService.java:211`, EQP `EquipmentService.java:148` | `page=2147483647` → `page*size` int 오버플로로 음수 OFFSET → 500 | long 계산 → 빈 200 | **수정** `0007e7f` |
| SAL-08 | Info | `StubAssignmentFeasibilityAdapter`(항상 OK), EQP `StubPilotQualificationAdapter`(항상 VALID) | 운영에서 스텁이 켜져 있으면 BR-SAL-011 · 조종 자격 미검증 | 운영 프로필에서 스텁 활성이면 기동 실패 | 미수정 |
| EQP-01~04 | Medium~Low | `ReservationService.java:53,104,109-128`, `CheckoutService.java:76,89-91,188,205`, `DroneService.logFlight`, `CalibrationService.java:131-151` | §2 참조 | 정책 | 정책 |
| EQP-05 | Low | `EquipmentService.issueLabel` | `serialNo` 에 `^FS^XA…` 를 넣어 라벨 프린터 명령 주입 | `^` · `~` 제거, 줄바꿈은 공백으로 | **수정** `0007e7f` |

**확인 결과 양호**
- SAL 28개 · EQP 24개 엔드포인트 모두 권한이 있고, 할인 승인은 EXEC 전용이며 If-Match 가 필수입니다.
- 대가 조건식은 고정 정규식 1개, 변수 화이트리스트, 리터럴 `[\w.]+` 이고 SpEL 이 없습니다.
- **QR.** 장비별 32바이트 SecureRandom 키를 쓰고, DTO · 감사 기록에 노출하지 않습니다. `MessageDigest.isEqual` 상수 시간 비교, 엄격한 파싱, 대체 키 없음입니다.
- **나라장터.** URL 은 설정에서만 받고, 키는 `${G2B_SERVICE_KEY:}` 이며 로그에 남기지 않습니다. 백필은 31일 · 200쪽 상한입니다.
- 검색은 `plainto_tsquery` 를 씁니다.
- 소비자는 `@IdempotentListener` + 부분 유니크 인덱스로 중복을 막습니다.

## 8. RPT · QAR · DEM · FLD · AIP

| ID | 심각도 | 위치 | 시나리오 | 조치 | 상태 |
|---|---|---|---|---|---|
| RPT-01 | High | `RptAccess.java:50-53`, `RptController.java:137-257`, `RenderService.java:186-194` | (= PTL-01) CUSTOMER 가 QA 전 초안 PDF · 섹션 · 등급 · 편집자를 조회 | CLIENT 범위는 발행 리비전만(목록 · 상세 · 파일 · DXF · 앵커 · 편집자), 나머지 404 | **수정** `67da56a` |
| RPT-02 | High | `RptController.java:56`, `SnapshotService.java:181-237`, `PublishService.java:154-157`, FAC `GradeRecordingService.java:50-64`, `ReportService.java:60-66` | 프로젝트 A 의 ENG/PM 이 스냅샷에 프로젝트 B 고객의 건물 UUID 를 넣고 정상 흐름을 마침 → 발행 시 B 건물에 E 등급 · 사용제한 · `GradeCriticalDetected` | `FacilityOwnershipGuard`: 생성 · 동결 때 시설물 · 건물 소유 고객 = 프로젝트 발주 고객인지 확인, 다르면 422 `RPT-422-001`(소유 고객이 없는 건물은 통과 → RPT-02b 정책) | **수정** `67da56a` |
| RPT-03 | Medium | `rpt/…/domain/drawing/DefectMapPng.java:66-68` | 같은 X 에 Y 를 1e9 떨어뜨린 결함 → 1200×4e8 래스터 → OOM 으로 JVM 중단 | 높이 ≤ 4×폭, 두 축 공통 축척, 비유한 좌표는 무시 | **수정** `67da56a` |
| RPT-04 | Low | `RenderService.java:57`, `PublishService.java:106`, `RptSubscriptionContracts.java:15` | IN_REVIEW · APPROVED 상태에서 재렌더가 가능하고 발행은 본문 해시만 비교 → WORM 발행본이 QA 가 본 PDF 와 바이트 단위로 다를 수 있음 | 승인 이벤트의 `pdfSha256` 을 전달받아 그 렌더를 발행하거나, IN_REVIEW 이후 재렌더 금지 | 미수정 |
| RPT-05 | Low | `PublishService.java:180-186` | 납품확인 `deliveredAt` 을 미래 · 과거로 임의 지정, `receiptAttachmentId` 소유 미확인 | `published_at ≤ deliveredAt ≤ now`, 첨부 소유 확인 | 미수정 |
| RPT-06 | Low | `RptController.java:224` | 건물 · 층 코드(패턴 없음)를 Content-Disposition 에 그대로 연결 | `ContentDisposition` 빌더(UTF-8) | **수정** `67da56a` |
| RPT-07 | Low | `ComposeService.java:279` | 등급 결정자가 지정 책임기술자가 아님 | 정책(§2) | 정책 |
| RPT-08 | Info | `HwpxPackage.java:82-110` | 검증용 압축 해제에 크기 · 엔트리 상한 없음(현재 입력은 서버 렌더 · 클래스패스 템플릿뿐) | 템플릿 업로드 기능 전에 상한 추가 | 미수정(잠재) |
| RPT-09 | Info | `AiReviewService.java:298-330` | 보고서 본문 프롬프트 주입은 제안에만 영향(CitationGuard + QA 채택 필요) | 조치 불필요 | 해당 없음 |
| QAR-01 | Medium | `WorkflowEngine.java:243-245` | 작성 · 편집 PM 이 최종 승인 | 정책(§2) | 정책 |
| QAR-02 | Medium | `AssignmentService.java:187-190` | 승인 SoD 조회가 `decision IS DISTINCT FROM 'REASSIGNED'` 로 재배정된 QA 를 빠뜨림(주석은 "재배정 포함"), 이전 라운드 QA 도 빠짐 → QA+PM 겸직자가 QA 검토 후 재배정되면 최종 승인 | 검토의 모든 라운드에서 QA_REVIEW 배정자였던 사람은 모두 제외 | **수정** `67da56a` |
| QAR-03 | Medium | `AssignmentService.java:137-162`, `V0024__qar_seed.sql:114` | 수동 배정이 검토자 자격을 확인하지 않음 → QA 가 비 QA 직원(그 프로젝트 PM)을 검토자로 지정하면 그 사람이 PASS | 활성 `qar.reviewer_profile` 필수 → 422 `NOT_A_REVIEWER` (누가 재배정하는지는 QAR-03b 정책) | **수정** `67da56a` |
| QAR-04 | Low | `WaiverService.java:31-66` | 승인(APPROVED) 뒤 · 지난 라운드 결과에도 waiver 추가 | APPROVED 또는 지난 라운드면 409(기존 시험이 SELF_CHECK 단계 waiver 를 허용하므로 "QA_REVIEWING/PENDING_APPROVAL 만" 은 적용하지 않음) | **일부 수정** `67da56a` |
| QAR-05 | Low | `CommentService.java:147-161` | 닫힌 · 지난 라운드 코멘트 상태 변경 | 현재 라운드 · 활성 검토만, 아니면 409 `QAR-409-006` | **수정** `67da56a` |
| DEM-01 | High | `DemController.java:246-251` → `DailyLogService.java:421`, `V0002:65,82,129` | 비투입 PM/ENG · 다른 고객 CUSTOMER 가 일지 id 로 전체 조회 | `DemAccess`: 일지 → 패키지 → 프로젝트를 해석해 범위 판정(PROJECT = 투입자 · 작성자 · 활성 감리자, CLIENT = 발주 고객) | **수정** `d210d5a` |
| DEM-02 · 03 | Medium | `V0029__dem_seed.sql:45-68`, `SupervisorService.java:62-126`, `DailyLogService.java:531-553`, `StructuralService.java:172-188,337-355` | §2 참조 | 정책 | 정책 |
| DEM-04 | Medium | `EvidenceService.java:60-91` | 아무 첨부(다른 모듈 · 프로젝트)나 증거로 등록, sha 가 같으면 다른 패키지 사진 정보를 반환 | 첨부가 `owner_module='DEM'` 이고 업로더가 본인이어야 함, 다른 패키지 중복은 409 `DEM-409-008`(정보 비노출) | **수정** `d210d5a` |
| FLD-01 | Medium | `SyncService.java:188-193,222-225,960` | 클라이언트 HLC `wallMs` 를 먼 미래로 보내면 모든 LWW 에서 이기고 `successor()` 자릿수가 넘쳐 정렬이 깨짐(경고만 남김) | push 는 서버 시각 + 24h(`atom.fld.sync.maxFutureSkew`) 를 넘으면 REJECTED `HLC_IN_FUTURE`, resolve 는 서버 HLC 사용 | **수정** `d210d5a` |
| FLD-02 | Medium | `PhotoUploadService.java:94-95,140-145,188,205`, `FldController.java:238` | 신고 `sizeBytes` 상한 · 세션 수 상한 없음, PATCH 에서 `expires_at` 미검사, 만료 세션 정리 없음, `@RequestBody byte[]` 를 5MB 검사 전에 전부 메모리로 읽음 → TEMP 디스크 · 힙 고갈 | 사진 50MB 413 `FLD-413-002` · 사용자당 열린 세션 20 → 429 `FLD-429-001` · 만료 세션 PATCH 410 `FLD-410-001` · `FldUploadJanitor`(만료 → EXPIRED, 임시 청크 삭제) · PATCH 본문은 Content-Length · 읽은 양 모두 청크 상한(5MB) 안에서만 읽음 · 수치는 설정(FLD-02b) | **수정** `abbe1b7` |
| FLD-03 | Low | `FldController.java:132-137` → `SurveyService.java:165` | 비투입 PM 이 다른 프로젝트 구역의 결함 코드 · 충돌 · 사진 id 를 조회 | `requireProject("fld:zone:lock")` | **수정** `d210d5a` |
| FLD-04 | Low | `SyncService.java:236-241` | opId 가 전역이라 남의 opId 를 재사용하면 그 사람의 결과(defectId · seq · conflictId)를 받음 | 같은 행위자 · 같은 라운드만 재반환, 아니면 REJECTED `OP_ID_IN_USE` | **수정** `d210d5a` |
| FLD-05 | Low | `PhotoUploadService.java:106-108,117-122` | `defectId` 를 라운드와 대조하지 않고 MIME 을 신뢰, 해시 충돌 시 다른 프로젝트 `existingPhotoId` 노출 | 다른 회차 defectId 422 · 조립 원본 매직 바이트 ≠ 신고 MIME 이면 REJECTED(422 `FLD-422-004`) · 다른 회차 사진이면 existingPhotoId 비노출 | **수정** `abbe1b7` |
| AIP-01 | High | `aip/…/ModelRegistryService.java:81-89`, `AipController.java:225-229` | 승인 역할을 본문(`{"role":"EXEC"}`)에서 받아 QA 혼자 QA · EXEC 두 승인을 채우고, 기존 승인도 덮어씀 | 본문 역할이 호출자의 실제 역할이 아니면 403, 이미 있는 승인 덮어쓰기는 409 `AIP-409-002` (동일인 두 승인 금지는 AIP-01b 정책) | **수정** `d210d5a` |
| AIP-02 | High | `ModelRegistryService.java:126`, `ModelStage.java:43-48` | SHADOW→CANARY 에서 `trafficPct=100` | 정책(§2) | 정책 |
| AIP-03 | High | `AipController.java:97-104`, `JobService.java:101-115`, `JobExecutor.java:76-87`, `SuggestionService.java:58-72,116` | `inputs[0].attachmentId` 는 아무 첨부나 받고(소유 · sha 확인 없음), 실제 분석은 클라이언트가 보낸 `params.imageB64` 로 함. `prompt` · `labels` 로 결과를 조종 → 다른 프로젝트 사진에 가짜 "균열 검출" 제안을 붙이고, projectId=null 이면 본인이 채택해 학습 큐로 보냄 | Job 생성 때 입력 첨부 소속 프로젝트(core `AttachmentReadApi` + 소유자 해석) = Job 프로젝트 · sha = 기록 해시, 사진 작업은 projectId · 첨부 필수. 워커 이미지는 언제나 서버 저장 원본(`JobImageLoader`, 해시 재확인 · 15MB), `params.imageB64` 는 무시 · 보관 안 함, 웹 요청의 `imageB64 · prompt · labels · model · findingModel` 은 422. FLD 동기 분석도 같은 경로 | **수정** `0c1b4d1` |
| AIP-04 | Medium | `JobService.java:95-99,129-148`, 인덱스 `V0031:66` | 작업 멱등 키가 전역 → 다른 사용자가 같은 키를 쓰면 남의 jobId · 상태를 받음 | 웹 요청 충돌은 409 `AIP-409-003`, jobId 비노출(인덱스는 전역 그대로 — RPT/QAR 업무 키 공유에 의존, 코드 주석으로 남김) | **일부 수정** `d210d5a` |
| AIP-05 | Medium | `AipAccess.java:46-47`, `AipController.java:118,188` | projectId 가 없는 작업 · 제안을 PROJECT 범위 PM/ENG 누구나 읽기 · 취소 · 결정 | 요청자 또는 ALL 범위만 | **수정** `d210d5a` |
| AIP-06 | Medium | `services/aip-worker/src/atomeng_aip/app.py`(인증 없음, 127.0.0.1 바인딩은 docstring 에만), `HttpAipWorkerClient.java:35-41`(10/8 · 172.16/12 · 192.168/16 허용) | 워커가 LAN 에 바인딩되면 같은 망의 누구나 `/internal/v1/*` 로 vLLM 작업을 실행 | ERP: 모든 워커 요청 HMAC-SHA256 서명(`WorkerRequestSigner`), 비밀(`ATOM_AIP_WORKER_SECRET`) 32바이트 미만 · 미설정이면 기동 실패, 워커 주소는 루프백 + `atom.aip.worker.allowed-hosts` 정확 일치만(사설망 대역 허용 삭제) · 리다이렉트 금지. 워커: `auth.py` 미들웨어(health 제외) — `compare_digest` · ±300초 · nonce 재전송 차단 · 비밀 없으면 전부 401 `AIP-401-001`, OpenAPI 계약 `AtomHmac` | **수정** `0c1b4d1` |
| AIP-07 | Medium | `schemas.py:33,59,87-88`, `vision/synth.py:97-103`, `JobExecutor.java:56-61,103` | `imageB64` 길이 상한 없음, `cv2.imdecode` 픽셀 상한 없음(압축 폭탄), 클라이언트 `model` 이 레지스트리 라우팅을 우회해 감사 출처가 틀어짐 | 워커로 가는 이미지는 서버 원본(15MB 상한) · 웹 `model`/`prompt`/`labels` 재정의 422 · `findingModel` 고정 · `maskB64` 15MB 상한. 워커 `cv2.imdecode` 픽셀 상한은 미수정 | **일부 수정** `0c1b4d1` |
| AIP-08 | Info | `SuggestionService.java:253` | 라벨 판정자 ≠ 라벨러 | 정책(§2) | 정책 |

**확인 결과 양호**
- **RPT/QAR 엔드포인트.** 전부 권한이 있고, 경로 id 는 모두 프로젝트로 해석해 `RptAccess` · `QarAccess` 를 거칩니다. QAR 은 CLIENT 범위를 거부합니다.
- **QAR 직무 분리.** QA 배정 SoD(작성 · 제출 · 편집 · 다른 단계 배정자 제외)와 위반 감사가 정상입니다. PENDING_APPROVAL 결정은 PM 만 하고, BLOCKING · 미해결 중대 코멘트 · 승인자 자격 · 리비전 불변 검사가 있습니다. QAR 승인 없이는 발행할 수 없습니다(APPROVED 는 내부 리스너로만 도달).
- **XXE.** `Owpml.parse` 에서 disallow-doctype · secure-processing · XInclude 해제 · 엔티티 확장 해제가 설정되어 있습니다. Transformer · Validator · SchemaFactory 는 외부 DTD 를 차단합니다.
- **압축 해제 · DXF.** 디스크로 압축을 풀지 않아 zip-slip 경로가 없습니다. DXF 텍스트는 `ascii()` 로 CR/LF 를 제거합니다.
- **Gotenberg.** URL 은 설정에서만 받습니다. HTML 이 아닌 HWPX(LibreOffice)를 넘기고, 사용자 텍스트는 `setTextContent` 로 넣습니다. 따라서 HTML 경유 SSRF 경로가 없습니다.
- **DEM.** 일지 작성(upsert)은 활성 감리자만 할 수 있고, clientOpId 재생은 행위자 · 패키지로 한정되며 advisory lock 으로 직렬화됩니다.
- **FLD.** 라운드 · 구역 · 결함 · push/pull · 충돌 · 업로드에 프로젝트 범위 검사가 있습니다.
- **FLD 업로드 오프셋.** offset 은 받은 양과 정확히 같아야 하고, 신고 길이를 넘는 청크는 거부합니다. 임시 키는 `fld/{uuid}/{%015d}` 라 경로 이탈이 없고, 최종 SHA-256 은 서버가 재계산합니다.
- **AIP 워커.** pickle · yaml · eval · subprocess · 파일 열기 · CORS 가 없습니다.
- **SQL · 소비자.** 동적 SQL 은 화이트리스트 컬럼과 상수 테이블명만 씁니다. `hrq.credential.expired` · `hrq.employee.terminated` · `fld.survey.completed` 소비자는 `@IdempotentListener` 로 중복을 막습니다.

## 9. 빌드 · 시험

- 1차: `./gradlew build` **BUILD SUCCESSFUL**, 시험 848개 실패 0 · 건너뜀 1.
- **2차(`444aad2` 기준): `JAVA_HOME=<PyCharm JBR> ./gradlew build` BUILD SUCCESSFUL — 시험 866개 중 실패 0, 건너뜀 1(PostGIS).** 실워커 계약 시험(`AipWorkerLiveContractTest`, .venv 의 uvicorn 을 시험 동안만 127.0.0.1 에 띄움)이 Java 서명을 실제 Python 검증기로 확인합니다.
- `services/aip-worker`: `uv run pytest` **50 passed**(1차 41 + 인증 9). `uv run python tools/export_openapi.py` 로 `contract/openapi.json` 재기록.
- 2차에서 고친 기존 시험(버그에 기대던 부분만):
  - `AuthzProbeController` 의 역할 프로브 2개(`cst:expense:create` EMPLOYEE=SELF, `aip:label-task:create` LABELER=SELF)는 범위 판정 수단이 없어 이제 403 → 역할만 보는 프로브라 `scopeCheckedInService = true` 를 붙임.
  - `AttachmentIntegrationTest` · `DemIntegrationTest` 는 임의 UUID 를 `chainScope` 로 써서 아무 체인에나 첨부를 붙였음(CORE-01(a) 그 자체) → 실제 FLD 회차 · DEM 업무 패키지와 투입/감리자로 바꿈.
  - `AipIntegrationTest` 는 소유자 없는 합성 첨부 행 + 클라이언트 `imageB64` 로 분석했음(AIP-03 그 자체) → 프로젝트 회차 첨부 + 저장 원본으로 바꾸고, 감사 `imageSha256` 대신 입력 참조 sha 를 확인.
  - `AipWorkerContractTest` · `AipWorkerLiveContractTest` · Python `conftest.py` · `test_live_vllm.py` 는 서명 · 서버 원본 이미지에 맞춰 클라이언트 생성 방식만 바꿈.
  - `KeycloakRealmExportTest.claimNames` 는 클레임 이름이 없는 audience 매퍼를 건너뜀.
- 운영 배포 시 꼭 할 일(추가): `ATOM_AIP_WORKER_SECRET`(32바이트 이상, ERP · 워커 같은 값), `ATOM_SECURITY_AUDIENCES`(realm 매퍼와 같은 값), `SPRING_PROFILES_ACTIVE=prod` + `atom.evidence.local-root`(영구 경로), Keycloak User Profile 에서 `client_id` · `employee_id` 관리자 전용(`deploy/keycloak/README.md`).
- 운영 배포 시 꼭 할 일: **`atom.fcd.token-hmac.v1` 에 32바이트 이상 비밀 키 주입**(FCD-01). 주입하지 않으면 OIDC 모드 기동이 실패합니다.

## 10. 다음 단계 권장 순서

2차에서 1 · 3 · 5 · 6(CMN-05 · AIP-03 · AIP-06 · FLD-02 · CORE-01)을 마쳤습니다. 남은 순서:

1. **PTL-01 정책 확정.** CUSTOMER 가 내부 API 를 쓰지 못하게 하고 포털로만 받게 합니다.
2. **HRQ-01 · HRQ-02** 타임시트 승인 · 정정 정책을 확정합니다(원가에 직접 영향).
3. **SAL-05** 결정 — 지금은 PM 이 영업기회 · 장비 엔드포인트 12개에서 403 입니다(실패하는 쪽으로 닫힘).
4. **AIP-02 · AIP-01b** 모델 승격 정책, **AIP-07** 나머지(워커 `cv2.imdecode` 픽셀 상한).
5. 미수정 Low/Info(CMN-08 · 10 · 11 · 12 · 13, FCD-08, RPT-04 · 05 · 08, SAL-08).
