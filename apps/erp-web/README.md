# apps/erp-web — 에이톰 ERP 웹 (1 · 2 · 3 · 4차 화면)

React 18 + TypeScript + Vite + TanStack Query + AG Grid Community + sonner(토스트) + uPlot(선 그래프, MIT · 약 50KB). 유료 의존성 없음. 로컬 전용(127.0.0.1).

## 실행

```bash
cd apps/erp-web
npm install

# 1) 백엔드: 내장 PostgreSQL + local-noauth(헤더 인증) + 합성 시드 → http://127.0.0.1:8081
#    JAVA_HOME 은 이 명령의 자식 프로세스에만 적용(기본: PyCharm 번들 JBR). 시스템 설정은 바꾸지 않습니다.
npm run backend          # 또는 저장소 루트에서 ./gradlew :apps:erp-server:devServer

# 2) 웹: http://127.0.0.1:5173 (/api → 127.0.0.1:8081 프록시, ATOM_ERP_BACKEND 로 변경)
npm run dev
```

검증:

```bash
npm run build            # tsc -b + vite build
npm test                 # Vitest + MSW 계약 목(Idempotency-Key · ETag/If-Match · problem+json)
npm run e2e              # Playwright(설치된 Edge) — 백엔드 · Vite 를 자동으로 띄우고 실제 API 로 20개 파일 흐름 구동
```

E2E 는 새 DB(백엔드 새로 기동)를 전제로 합니다. 이미 떠 있는 서버를 재사용하면 시드 상태가 바뀌어 일부 시험(타임시트 · FCD)이 실패할 수 있습니다. 스크린샷은 `e2e/screenshots/`.

## 인증

| 모드 | 설정 | 동작 |
|---|---|---|
| dev (기본) | `VITE_AUTH_MODE=dev`, 백엔드 `local-noauth` | 상단 선택 상자로 합성 사용자 전환 → `X-Actor-Id` · `X-Roles` 헤더. 사용자 ID 는 `src/auth/devUsers.ts` = 백엔드 시드(`DevSeed`). 고객 사용자 2명(`(고객A) 한빛 담당` · `(고객B) 푸른 담당`, CUSTOMER)은 고르면 고객사 ID 를 이름으로 찾아(dev 전용, ADMIN 헤더로 `GET /core/clients`) `X-Client-Org-Id` 를 함께 보내고 `/portal` 로 이동 |
| oidc | `VITE_AUTH_MODE=oidc`, `VITE_OIDC_AUTHORITY`, `VITE_OIDC_CLIENT_ID` (`.env.example`) | oidc-client-ts PKCE(공개 클라이언트 `erp-web`, 비밀값 없음) → `Authorization: Bearer`. 역할은 토큰 `roles`, 직원 ID 는 `eid` 클레임 |

## API 규약 (`src/api/http.ts`)

- POST: `Idempotency-Key` 자동(UUID). 같은 사용자 동작의 재시도는 같은 키(예: 타임시트 정정은 대화상자마다 키 1개).
- PUT/PATCH: 같은 경로(또는 `etagFrom`)에서 마지막으로 받은 `ETag` 를 `If-Match` 로 자동. 낙관적 잠금이 필요한 상태 전이 POST(`:move`, `:approve-discount`, QAR 제출 · 승인, 예산 승인)는 응답 `version` 으로 `If-Match` 를 명시.
- 오류: `application/problem+json` → `ProblemError` → 토스트 `[CORE-xxx-nnn] 제목` + detail · 필드 오류 · traceId. 화면 안 403/404 는 카드 안에 안내.

## 화면

| 메뉴(그룹) | 경로 | 모듈 |
|---|---|---|
| 공통 › 프로젝트 | `/projects`, `/projects/:id` | CORE 목록(투입 범위) · 투입 지정, CST 실행예산 승인 · EVM · 수익성 · 원장 |
| G4 › 영업기회 · 견적 | `/sal/estimates` | 대가 산정 → 견적 버전 → 발행 · 할인 한도 초과 시 EXEC 승인 → 재발행 · 송부/수락 |
| G5 › 타임시트 | `/hrq/timesheets` | ENG 주간 입력 · 제출, PM 승인, 정정(리비전 이력) |
| G3 › 법인카드 검토 큐 | `/fcd/cases` | 명세서 업로드 · 검토 큐 실행, 소명 요청 → 본인 소명 → 감사 판정 |
| G2 › 보고서 · QAR | `/reports`, `/reports/:id` | 스냅샷 → 작성 · 등급 → 렌더 · 검토 요청 → QAR(자체검토 · AI · QA · 승인) → 발행 · 납품 |
| 공통 › 관리 › 영업팀장 그룹 (ADMIN) | `/admin/sales-group` | SAL 영업팀장(할인 한도 10%) 소속 등록 · 메모 수정 · 비활성화/재활성화(If-Match) |
| 공통 › 관리 › QA 검토자 (ADMIN) | `/admin/qa-reviewers` | QAR 검토자 프로필(동시 상한 · 전문성 RT_…=건수 · 자격) 등록 · 수정 · 비활성화 |
| 공통 › 관리 › 표준 노임 단가 (ADMIN · EXEC · FIN · SYSADMIN) | `/admin/labor-rates` | CST 기준 요율 유효기간표: ADMIN 초안 등록 · 수정 · 적용 종료, EXEC 승인 · 반려(등록자 본인 승인 불가) |
| G2 › 시설물 | `/fac/buildings`, `/fac/buildings/:id` | FAC 목록 · 검색, 등록 마법사(주소 조회 → 대장 표제부 · 다동 선택, 장애 · 주소 없음 → 수기 입력, 중복 후보 409 → 사유 남기고 강제), 상세: 위치 트리(ltree 경로) · 등급 이력(읽기 전용) · 점검 도래 · 법적 분류 확정 · 병합 대화상자 |
| G2 › 점검 도래 (EXEC · SALES · PM) | `/fac/dues` | 전 시설물 도래 D-day 배지(경과 · 30일 빨강, 90일 주황), 기간 필터 |
| G5 › 장비 · 교정 | `/eqp/equipment` | 교정 상태 칩(유효 · 임박 · 만료 · 이력 없음 · 불필요), 교정 만료 목록 탭, ADMIN QR 라벨 토큰 발급 |
| G5 › 장비 예약 · 반출 (PM · ENG · ADMIN · EXEC) | `/eqp/reservations` | 21일 예약 달력(칸 클릭으로 기간 선택), 예약 요청 — 겹침은 미리 경고 · 서버 409(EQP-409-002/009/011)는 화면 안 배너 + 대안 장비 버튼, 승인 · 취소, ENG QR 토큰(라벨 URL 붙여넣기) → 스캔 → 반출(clientOpId) → 반납 |
| G6 › 해체 · 감리 | `/dem` | 프로젝트 → 업무 패키지, 감리원 지정(배정 전 HRQ 자격 확인 · 만료/임박 배지 · 422 사유 배너 · 공백/알림 배너), 감리일지(잠금 🔒 · 잠금 전, 상세 읽기 전용), 도서 리비전 · 트랜스미털 발행 · 검토의견(회신 · 수용 · 종결) — 미결 의견이 있으면 시공용(FOR_CONSTRUCTION) 발행 버튼 차단 |
| G1 › 현장조사 (관리) | `/fld` | 데스크톱 관리 보기: 회차 · 구역 잠금 상태와 막는 사유(필수 측정 누락 · 미해결 충돌 · AI 제안 · 사진 거부), 충돌 해결 대화상자(서버 값 유지 · 기기 값 채택 · 직접 값 병합, opId 1개), 사진 검증 상태(기기/서버 해시 · 다시 계산) |
| G5 › 인력 · 자격 | `/hrq/people` | HRQ 직원 대장, 자격 목록 — 만료 칩(유효 D-n · 90일 내 임박 · 만료 D+n · 무기한 · 승인 대기), 상태 필터, ADMIN 승인 · 반려(사유 필수 대화상자) |
| G5 › 등록요건 · 예측 | `/hrq/compliance` | 오늘 분야 · 등급별 충족(필요 · 배정 사번 · 부족), 1년 예측 계단 차트(uPlot) + **최초 미달일 표시선**, 자격 유효 현황(보유자 · 유효 · 승인 대기 · 만료 예정 변화점). ⚠️ 예시 규칙 배너 |
| G5 › HRQ 월 마감 (EXEC · PM · ADMIN) | `/hrq/month-close` | ADMIN 월 마감(미승인 있으면 HRQ-409-004 토스트) · 이력 · 재오픈(사유 5자 이상) |
| G3 › 원가 회계기간 · 대사 | `/cst/periods` | ADMIN 마감 — 체크리스트 미충족이면 막는 사유(HRQ 월 마감 전 · 대사 없음 · 불일치)를 화면 안에, SYSADMIN 재오픈(월 입력 + 사유 — 목록 권한 없음), 기간 목록(HRQ 마감 수신 · 대사 MATCHED/MISMATCH · 인·일), 행 클릭 → 대사 실행 이력 · 불일치 상세 |
| G3 › 원가 원장 · EVM | `/cst/ledger` | 프로젝트 · 전기일(서버) · 귀속월 · 원천 · 원가요소 · 기술자 필터, **정정 묶음 강조**(원 행 · 역분개 · 같은 업무 멱등키의 정정 후 행 — 색 + `#n` 배지, "정정 짝만"), 실원가 마스킹 안내, EVM 추이(PV · EV · AC + 오른쪽 축 CPI · SPI, 실단가 권한 없으면 표준 AC · CPI(표준)) |
| G3 › 법인카드 가져오기 · 중복 | `/fcd/imports` | 배치 목록 · 업로드 · 확정 · 폐기, **행 오류 리포트 CSV 다운로드**(서버 CSV 그대로 — BOM · 수식 주입 무력화 `'=1+2`, 화면에 앞 몇 줄 미리보기), 목록 CSV 내보내기(같은 규칙 `toSafeCsv`), 중복 확인 큐(기존 ↔ 새 거래 · 점수 · 특징 → 병합/별개 유지) |
| G3 › 탐지 룰셋 (4-eyes) | `/fcd/rulesets` | 버전 목록(오늘 적용 버전 · 작성자 · 승인자 · SHA-256), YAML 초안 등록(FIN · AUDITOR), 승인(AUDITOR · EXEC) — **작성자 본인은 버튼 비활성 + 이유**, 서버도 FCD-403-002 |
| G3 › 법인카드 목록 | `/fcd/cards` | 마스킹 번호만(긴 숫자열이 오면 화면에서 다시 가림), 한도 · 소지자 · 퇴사자 비활성 권고 |
| G1 › AI Job · 모델 · 제안 | `/aip?tab=jobs\|models\|suggestions\|audit` | Job 상태 칩 · 상태 필터 · 상세(job_event 이력) · 대기 Job 취소, 모델 레지스트리(단계 · 별칭 shadow/canary/production · 트래픽 %, QA/EXEC 승인 기록, SYSADMIN 단계 전이 대화상자, 승격 요청 표), 제안(결정 대기 → 수락/거절 사유, 결정됨), 추론 감사(AUDITOR — **해시 · 모델 · 지연만**, params · provenance 는 화면 데이터에서 제거) |
| G2 › 보고서 리비전 상세 | `/reports/:id/revisions/:revId` | 블록 본문 읽기 보기(🔒 수치 블록 · 자동/사람 편집 · 표 블록), **앵커 오버레이**(A4 쪽 축소판 위 추정 위치 상자 — 누르면 블록 강조, 목록), 등급 트리(동 → 층 → 부재 + 근거 지표 · 구간 상한 · 결함), HWPX · 층별 DXF 다운로드 |
| **4차** G4 › 영업 파이프라인 (SALES · EXEC · ADMIN) | `/sal/pipeline?tab=board\|notices\|sim` | 단계별 칸반(건수 · 가중 금액) — 단계 버튼 · 끌어 놓기 → `:move`(응답 version 으로 If-Match), LOST/DROPPED 는 사유 대화상자, 서버 403/409/412/422/428 은 보드 안 배너(412 는 다시 읽기). 입찰 품의 승인(BID_APPROVED)은 EXEC `:approve-bid`, WON · CONTRACTED 는 견적 수락 · 계약 서명으로만(SAL-03). 나라장터 공고 목록(상태 · 키워드 서버 검색 + 마감 N일 · 최저가 · 정정/취소 숨김), 수집 로그 · ADMIN/SYSADMIN "지금 수집", SALES "기회 만들기". 적격심사 모의계산(항목별 점수 · 투찰률 시나리오 · 추천 투찰률 · 사용한 실적) |
| **4차** G6 › 감리일지 작성 · 정정 (PM · ENG · EXEC · QA · ADMIN) | `/dem/logs` | 점검표 템플릿 · 공정 · 공정률 · 인원 · 날씨 · 특기 사항. **초안은 localStorage** `atom-erp.dem-log-draft.{wp}.{date}` 에 저장 · 초안마다 고정 clientOpId → 네트워크 실패면 "전송 대기", `online` 이벤트(또는 버튼)로 **같은 clientOpId** 재전송(서버 `Idempotent-Replayed`). 잠긴 일지는 정정 요청(항목 · 새 값 · 사유 필수) — 본인 일지 정정은 "승인 대기", 요청자 · 일지 작성자가 아닌 PM/EXEC 가 승인 · 반려(반려는 메모 필수, 늦은 동기화 초안은 반려만 — DEM-03b) |
| **4차** G1 › 현장조사 회차 설정 (PM · ADMIN) | `/fld/setup` | 회차 만들기(프로젝트 · 건물 · 유형 · 증거 모드 · 시작일 · 회차 번호, 재시도는 같은 Idempotency-Key) → 구역 추가(이름 · FAC 층 노드 · 프로젝트 투입자 담당), 서버 403/409/422 는 화면 안 |
| **4차** 공통 › 내 할 일 (모두) | `/inbox` + 머리글 배지 `내 할 일 N` | 지금 사용자가 처리할 승인만 모음: 할인 승인(SAL 수신함 API) · 예산 결재(CST) · 타임시트(HRQ, RESP 인 프로젝트) · QAR 배정 · 기준 요율 승인 · FCD 룰셋 4-eyes · 감리일지 정정 · AI 모델 승인 — 본인이 만든 건은 빼고(직무 분리), 항목마다 처리 화면 링크 |
| 고객 포털 (CUSTOMER 전용, 별도 레이아웃) | `/portal`, `/portal/reports`, `/portal/requests`, `/portal/notifications` | 내 시설물 · 안전등급 · 점검 일정(D-day), 납품 보고서(정정본 대체 배너 · 열람만 / 최신본 워터마크 PDF — download-id · SHA-256 표시), 서비스 요청(긴급은 사유 필수 · 멱등 키), 알림 읽음. 직원이 /portal 에 오면 안내, 고객이 내부 화면에 오면 /portal 로 |

## 백엔드에 추가한 조회 API (웹 화면용)

4차(저장소 README 4-17 절, 읽기 전용 · 마이그레이션 없음): `GET /sal/inbox/discount-approvals` · `GET /cst/inbox/budget-approvals` · `GET /hrq/inbox/timesheet-approvals` · `GET /qar/inbox/assignments` — "내 할 일" 이 브라우저에서 "본인 것 제외 · RESP 인 프로젝트" 를 거를 수 없어 서버에서 실패하는 쪽으로 닫아 거름. dev 사용자 `겸직임원`(SALES+EXEC) · `겸직책임`(PM+ENG+EXEC) 추가(직무 분리 E2E), 시드 `DevSeedRound4`.

보안 정책 적용(저장소 README 4-16 절)으로 바뀐 화면: 견적 — 작성자 본인 EXEC 는 할인 승인 버튼 대신 안내 · 단계 버튼에서 WON/CONTRACTED/BID_APPROVED 제외, 장비 예약 — 본인 신청은 승인 버튼 숨김 · 취소는 신청자/PM, 프로젝트 상세 — 예산 "상신" → 결재(작성 · 상신자 본인은 비활성), HRQ 월 마감 재개 · CST 재오픈은 SYSADMIN(회계기간 목록 행에서), AI 모델 탭 SYSADMIN 표시 · canary 25% 상한 안내 · QA/EXEC 같은 사람 승인 버튼 숨김, FCD — 본인 카드 중복은 병합 · 분리 비활성, AUDITOR 만 검토 실행 재정의(사유), 직원에게 룰 적중 요약만, 보고서 — "나를 QA 검토자로 배정" 삭제(자기 배정 금지).

3차(저장소 README 4-15 절, 읽기 전용 · 마이그레이션 · 권한 시드 변경 없음): `GET /hrq/credentials?status=` · `GET /hrq/month-close` · `GET /cst/periods` · `GET /cst/periods/{ym}/reconciliations` · `GET /cst/projects/{id}/evm/snapshots` · `GET /fcd/imports` · `GET /aip/jobs?status=&serviceCode=` · `GET /aip/suggestions?status=`. dev 사용자에 `시스템관리`(SYSADMIN, D-013) 추가 — CST 재오픈 · AI 모델 단계 전이.

2차(저장소 README 4-14 절): `GET /fac/dues` · `GET /eqp/calibration-status` · `GET /dem/projects` · `GET /dem/work-packages/{wp}/daily-logs|documents|transmittals` · `GET /fld/rounds?projectId=` · `GET /fld/rounds/{id}/photos`. dev 서버는 공공 API WireMock 대역을 띄운다(주소 "합성로 1000~1999" 자동 입력, "2000~2999" 다동, 그 외 수기 입력).

1차:

- `GET /api/v1/core/projects` — `core:project:read`, 범위는 서비스가 거름(ALL 전체 · PROJECT 투입 중 · CLIENT 자기 고객사).
- `GET /api/v1/core/clients` — `core:client:read`(보유 역할 모두 ALL).
- `GET /api/v1/qar/reviews?reportId=` — `qar:review:read`, 검토 단건 조회와 같은 범위 검사.

관리 API(직접 SQL 대체, 저장소 README 4-13 절): `/api/v1/sal/sales-group-members`, `/api/v1/qar/reviewer-profiles`, `/api/v1/cst/rates`(초안 → EXEC 승인).
dev 시드는 영업팀장 그룹 · QA 검토자 · 표준 노임 단가(⚠️ 예시 합성 값, 초안 → 승인)까지 모두 API 로 만듭니다.

E2E `admin-rates.spec.ts`: ADMIN 이 중급 단가(⚠️ 예시 300,000원, 2주 전 월요일부터) 초안 등록 → EXEC 승인 → ENG 8시간 제출 → PM 승인 → 프로젝트 원가 원장에 표준원가 300,000원 행.

E2E 2차(새 DB 전제):
- `fac.spec.ts` — 주소 조회(합성로 13xx~19xx 무작위) 등록 → 위치 트리 → 분류 확정 → 점검 도래 목록 D-day (스크린샷 12~14, 다크 14)
- `eqp.spec.ts` — 겹침 예약 409 화면 안내 → 오늘~내일 예약 → ADMIN 승인 · QR 토큰 발급 → ENG QR 반출(예약 연결) → 반납 (15~18)
- `ptl.spec.ts` — 고객 B/A 전환 테넌트 격리(목록 · 교차 직접 조회 404 PTL-404-001) · 대체 배너 · 워터마크 PDF 다운로드(%PDF, rev2 파일명) · 알림 (19~22)
- `fld.spec.ts` — 충돌 2건 → 1F 구역 충돌 병합 값 해결 → 잠금 가능 → 잠금 (23~25)
- `dem.spec.ts` — 자격 임박 경고 · 만료 자격 지정 422 · 일지 잠금 · 시공용 발행 차단(읽기 위주) (26~28)

E2E 3차(새 DB 전제, 스크린샷 29~47):
- `fcd-ruleset.spec.ts` — 오감사(AUDITOR) 가 저장소 YAML 을 새 버전(2027 시행)으로 초안 등록 → 본인 승인 버튼 비활성 · 이유, 작성자 헤더로 직접 승인 API → 403 FCD-403-002 → 김임원(EXEC) 승인 → ACTIVE, 오늘 적용 버전은 그대로 (29~30)
- `hrq-credential.spec.ts` — 서경영(ADMIN) 자격 승인(정참여, 200일 뒤 만료) → 등록요건 화면 유효 자격 +1 · 승인 대기 −1 · 예측 변화점 +1, 다크 (31~33)
- `cst-close.spec.ts` — 지난달 CST 마감 시도 → 막는 사유(HRQ 월 마감 전) → HRQ 월 마감 → 대사 MATCHED 후 CST 마감 → 정참여가 그 달 타임시트 제출 → 화면 안 HRQ-409-001(전기 차단) → 시스템관리(SYSADMIN) CST 재오픈 · 서경영 HRQ 재오픈(사유) → 다시 제출 성공 (34~37)
- `round3-views.spec.ts` — 원가 원장 정정 묶음 · EVM 추이 · 다크(38~39), 행 오류 CSV 다운로드(BOM · `'=1+2`) · 중복 확인 병합 · 카드 마스킹(40~42), AIP Job · 모델 · 제안 수락 · 감사 해시(43~46), 리비전 상세 블록 · 앵커 · 등급 트리 · HWPX(`PK`) · DXF(`AC1009`) 다운로드(47)

Vitest 3차: `src/features/{hrq/hrq3,cst/cst,fcd/fcd3,aip/aip,rpt/revision}.test.tsx` — uPlot 은 `lineChartModule`(표 대역, jsdom 에 canvas 없음).

Vitest 2차: `src/features/{fac,eqp,dem,ptl,fld}/*.test.tsx` — 공통 도구 `src/test/mocks.tsx`(MSW · 요청 기록 · renderAs) · `src/test/mockModules.tsx`(sonner · AG Grid 대역, 앱 코드 import 없음 → vi.mock 순환 교착 방지).

모든 데이터는 합성(가짜) 데이터입니다.
