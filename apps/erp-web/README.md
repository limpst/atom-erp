# apps/erp-web — 에이톰 ERP 웹 (첫 단계)

React 18 + TypeScript + Vite + TanStack Query + AG Grid Community + sonner(토스트). 유료 의존성 없음. 로컬 전용(127.0.0.1).

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
npm run e2e              # Playwright(설치된 Edge) — 백엔드 · Vite 를 자동으로 띄우고 실제 API 로 6개 파일 흐름 구동
```

E2E 는 새 DB(백엔드 새로 기동)를 전제로 합니다. 이미 떠 있는 서버를 재사용하면 시드 상태가 바뀌어 일부 시험(타임시트 · FCD)이 실패할 수 있습니다. 스크린샷은 `e2e/screenshots/`.

## 인증

| 모드 | 설정 | 동작 |
|---|---|---|
| dev (기본) | `VITE_AUTH_MODE=dev`, 백엔드 `local-noauth` | 상단 선택 상자로 합성 사용자 전환 → `X-Actor-Id` · `X-Roles` 헤더. 사용자 ID 는 `src/auth/devUsers.ts` = 백엔드 시드(`DevSeed`) |
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

## 백엔드에 추가한 조회 API (웹 화면용)

- `GET /api/v1/core/projects` — `core:project:read`, 범위는 서비스가 거름(ALL 전체 · PROJECT 투입 중 · CLIENT 자기 고객사).
- `GET /api/v1/core/clients` — `core:client:read`(보유 역할 모두 ALL).
- `GET /api/v1/qar/reviews?reportId=` — `qar:review:read`, 검토 단건 조회와 같은 범위 검사.

관리 API(직접 SQL 대체, 저장소 README 4-13 절): `/api/v1/sal/sales-group-members`, `/api/v1/qar/reviewer-profiles`, `/api/v1/cst/rates`(초안 → EXEC 승인).
dev 시드는 영업팀장 그룹 · QA 검토자 · 표준 노임 단가(⚠️ 예시 합성 값, 초안 → 승인)까지 모두 API 로 만듭니다.

E2E `admin-rates.spec.ts`: ADMIN 이 중급 단가(⚠️ 예시 300,000원, 2주 전 월요일부터) 초안 등록 → EXEC 승인 → ENG 8시간 제출 → PM 승인 → 프로젝트 원가 원장에 표준원가 300,000원 행.

모든 데이터는 합성(가짜) 데이터입니다.
