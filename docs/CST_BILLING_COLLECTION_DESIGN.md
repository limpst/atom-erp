# CST 청구 · 수금 · 통장 대사 설계서 (08 Step 12·13 첫 단계)

판: 2026-11-08 · 브랜치 `feat/cst-billing-collection` · 상태: 1단계(스키마 · 도메인 · 시험 16개 통과) + 2단계(서비스 · API · 통합 시험) 구현, 3단계(독촉 · 분류) · 화면 · 이벤트 설계

## 쉬운 말 한 장 요약

- **결론**: 회사가 "받을 돈"을 매일 정확히 알 수 있게, 원가 모듈(CST) 안에 **청구(세금계산서) · 입금 · 짝 맞추기(대사)** 세 가지 표를 넣었다.
- 청구는 발행하면 금액을 고칠 수 없다. 통장 입금 기록과 짝 기록은 지울 수 없다. 잘못 짝지으면 "거꾸로 한 줄"(역분개)을 더한다.
- 짝을 **제안한 사람은 확정할 수 없다**. 다른 사람이 확인해야 한다(두 사람 규칙).
- "며칠째 못 받았나"(aging)는 **기준일을 정해** 계산한다. 그래서 같은 날짜로 다시 계산하면 언제나 같은 숫자가 나온다.
- 입금과 청구가 같은 건인지 **점수(0~100)** 로 알려 준다. 금액이 같으면 50점, 이름이 비슷하면 최대 30점, 날짜가 가까우면 최대 20점이다. 90점 이상만 "자동 짝 후보"이고, 확정은 언제나 사람이 한다.

## 배경 설명

1. 설계 08(CST)은 "경비 · 기성 · 청구 · 수금" 표를 Step 7~13 에서 추가하기로 했다(README 5장 편차 19번). 지금까지는 원가 장부(나간 돈)만 있었고, **들어올 돈**을 적는 곳이 없었다.
2. 2026-11 에 회사의 실제 운영 자료(계약 현황 · 세금계산서 관리대장 · 통장 내역 엑셀)를 따로 분석했다(Python 시제품 `limpst/atom_erp`, 이 저장소에는 실제 자료를 넣지 않는다). 거기서 나온 사실은 다음과 같다.
   - 2024~2026 계약의 잔여기성(아직 못 받은 계약 금액)은 약 108.9억 원이다.
   - 2026 년 세금계산서 257건 중 66건, 약 5.5억 원이 미입금이고 그중 30건 약 2.6억 원이 발행 90일을 넘었다.
   - 엑셀 대장의 "잔액" 칸은 빈 칸이 있고 합계 수식 범위가 어긋나 있어, 사람이 합계를 믿기 어렵다.
   - 통장의 "계정이 빈 출금" 15.3억 원은 전부 자기 계좌 사이 이동(대체)이었다. 분류 규칙이 없으면 현금 흐름을 잘못 읽는다.
3. 그래서 ERP 가 풀 문제는 세 가지다. ① 청구 금액을 누구도 몰래 바꾸지 못하게 한다. ② 입금과 청구를 짝지어 미수를 자동으로 계산한다. ③ 미수를 경과일로 나눠 오래된 것부터 챙긴다.

## 현황

| 항목 | 상태 |
|---|---|
| 스키마 `V0052__cst_billing_collection.sql` | 끝 — invoice · bank_receipt · receipt_allocation, 불변 트리거, `invoice_open_items(as_of)` · `receivable_aging(as_of)` |
| 권한 `V0053__cst_billing_permissions.sql` | 끝 — 세부 권한 19건(재무 제안 · 경영지원/임원 확정) |
| 도메인 `cst.domain.billing` | 끝 — `MatchScorer` · `AgingBucket` · `PartyName` (순수 도메인, 부동소수 없음) |
| 시험 | 끝 — `BillingMigrationTest` 6개(내장 PostgreSQL) · `BillingMatchGoldenTest` 4개(Python 합성 정답 16건 + aging 경계 12건) |
| 서비스 · API | 끝 — `BillingService`(쓰기 진입점 하나, ArchUnit) · `BillingController` API 10개 · `V0054`(allocation_proposal · invoice_no_seq) · `V0055`(PM receivable 삭제, 권한 18건) · `CstBillingIntegrationTest` |
| 이벤트 5종 · 웹 미수 현황 화면 · 내 할 일(확정 대기 제안) | 끝 — `CstEvents` 5종(Outbox) · `/cst/receivables` · `/cst/inbox/allocation-proposals` |
| 독촉 1단계 | 끝 — `V0056`(dunning_notice · dunning_hold · 상태 전이 트리거 `CST-409-031·032`) · `DunningService.run(asOf)`(멱등) · `/cst/dunning:run · /notices · :approve(EXEC·ADMIN, 작성자 제외) · :sent(APPROVED 뒤에만) · :hold · :release` |
| 통장 명세 업로드 | 끝 — `BankStatementParser` · `POST /receipts:upload`(입금 줄만, 재업로드 멱등, 잔액 검증) |
| 통장 줄 원장 · 계정 분류 | 끝 — V0057 `cst.bank_line` · `BankLineClassifier` · `/bank-lines` 4 API |
| 독촉 웹 화면 · 명세 업로드 · 출금 확정 패널 | 끝 — `/cst/dunning` · `/cst/receivables` 패널 2개 |
| 거래처 묶음(bank_line → BP) | 남음(9-3) |
| 독촉(dunning) · 통장 계정 분류 · 거래처 이름 묶음 | 남음(3단계) |

## 용어 정리

| 용어 | 쉬운 뜻 | 어떻게 읽나 |
|---|---|---|
| 청구(invoice) | 발주처에 "이만큼 주세요" 하고 끊은 세금계산서 1건 | 공급가액 + 부가세 = 합계(원) |
| 입금(bank receipt) | 통장 명세서의 들어온 돈 한 줄 | 입금일 · 금액 · 거래내용 |
| 대사(reconciliation) / 배분(allocation) | 입금을 어느 청구의 돈인지 짝짓는 일 | 한 입금을 여러 청구에, 한 청구를 여러 입금에 나눌 수 있다 |
| 역분개(reversal) | 잘못 적은 줄을 지우지 않고 같은 금액의 마이너스 줄을 더하는 것 | 원래 줄 1개에 역분개 1개만 |
| open item | 아직 다 못 받은 청구 | 합계 − 배분 합 > 0 |
| aging | 미수를 발행 뒤 지난 날수로 묶은 것 | A 0~30 · B 31~60 · C 61~90 · D 91~180 · E 181일 이상 |
| 기준일(as-of) | "이 날짜 기준으로" 계산하겠다는 날짜 | 같은 기준일이면 언제 계산해도 같은 값 |
| 4-eyes | 한 사람이 만들고 다른 사람이 확인하는 규칙 | 제안자 ≠ 확정자 |

## 1. 결정: 새 모듈이 아니라 CST 안에 둔다

- 설계 08 이 청구 · 수금을 CST 범위로 정했다. 수익성(`mv_project_profitability`)은 원가와 수금을 같은 프로젝트 키로 봐야 한다.
- 스키마는 `cst`, 번호는 전역 다음 번호 V0052 · V0053, 오류 코드는 `CST-4xx-02x` 대역을 쓴다.
- 다른 모듈과의 연결은 지금과 같다: 하드 FK 는 `core.project` · `core.client` 뿐이고, 영업(SAL) 계약 · 포털(PTL)과는 이벤트로 잇는다(2단계).

## 2. 데이터 모델

| 표 | 한 줄 뜻 | 핵심 규칙 |
|---|---|---|
| `cst.invoice` | 청구 1건 | `total_krw` 는 생성 열(공급가액 + 부가세). 발행 뒤 금액 · 거래처 · 프로젝트 · 발행일 · 번호 불변(`CST-409-020`). 취소는 `VOID`, 받은 돈이 남아 있으면 VOID 불가 · 되살리기 불가(`CST-409-021`) |
| `cst.bank_receipt` | 통장 입금 한 줄 | INSERT 만(`CST-409-022`). `(bank_account, statement_key)` 유일 → 같은 명세서를 다시 올려도 한 번만. 계좌번호 원문은 저장하지 않고 별칭만 |
| `cst.receipt_allocation` | 입금 ↔ 청구 배분 | INSERT 만(`CST-409-023`). 양수 = 배분, 음수 = 역분개(원 줄의 거울, 1회 — `CST-422-020`). 제안자 ≠ 확정자(`ck_alloc_4eyes`). 청구 합계 초과(`CST-409-025`) · 입금액 초과(`CST-409-026`) · VOID 청구 배분(`CST-409-024`) 거부 |

금액은 전부 `numeric(18,0)` 원 단위다. 부동소수 열은 없다(NFR-CST-02, 시험으로 확인).

## 3. 업무 규칙 (BR-CST-BIL)

| 번호 | 규칙 | 지키는 곳 |
|---|---|---|
| 001 | 발행된 청구의 금액 · 거래처 · 발행일은 바꿀 수 없다 | DB 트리거 `trg_invoice_guard` |
| 002 | 통장 입금 기록은 고치거나 지울 수 없다 | `trg_bank_receipt_immutable` |
| 003 | 배분은 지우지 않고 역분개 한 줄로만 취소한다 | `trg_alloc_immutable` · `ux_alloc_reversal` · `trg_alloc_guard` |
| 004 | 배분을 제안한 사람은 확정할 수 없다 | `ck_alloc_4eyes` + 권한(재무 = 제안, 경영지원 · 임원 = 확정) |
| 005 | 배분 합은 청구 합계와 입금액을 넘을 수 없다 | `trg_alloc_guard` (청구 행 잠금으로 동시 요청을 줄 세움) |
| 006 | aging 은 기준일 인자로 계산한다. 구간 A 0~30 · B 31~60 · C 61~90 · D 91~180 · E 181+ | `cst.invoice_open_items(as_of)` · `AgingBucket` |
| 007 | 이름 비교 전에 법인 표기(㈜ · (주) · 주식회사 …) · 공백 · 기호를 지운다 | `PartyName.normalize` |
| 008 | 짝 점수 = 금액 50(합계와 같음) 또는 35(공급가액과 같음) + 이름 최대 30(bigram Dice) + 날짜 최대 20(0~30일 20 · 31~90일 10 · 91~180일 5). 90점 이상만 자동 후보 | `MatchScorer` (Python 합성 정답과 패리티) |

## 4. 계산 예 (합성 숫자)

| 청구 | 입금 | 금액 | 이름 | 날짜 | 합 | 뜻 |
|---|---|---|---|---|---|---|
| 합성건설㈜ · 합계 11,000,000원 · 03-02 발행 | 11,000,000원 · "합성건설" · 03-20 | 50 | 30 | 20 | 100 | 자동 짝 후보. 다른 사람이 확인하면 확정 |
| (주)합성건설 · 합계 11,000,000원 | 10,000,000원(공급가액만) · 04-15 | 35 | 30 | 10 | 75 | 부가세가 빠진 입금. 사람이 판단 |
| 합성관리단 · 합계 3,300,000원 | 3,300,000원 · "전혀다른상사" · 9일 뒤 | 50 | 0 | 20 | 70 | 금액은 같지만 이름이 다르다. 자동 후보 아님 |

## 5. 권한 (V0053)

| 권한 | EXEC | ADMIN | FIN | AUDITOR | PM |
|---|---|---|---|---|---|
| `cst:invoice:read` · `cst:receivable:read` | 전체 | 전체 | 전체 | 전체 | 자기 프로젝트 |
| `cst:invoice:write` · `cst:receipt:write` · `cst:allocation:propose` | — | — | 전체 | — | — |
| `cst:receipt:read` | 전체 | 전체 | 전체 | 전체 | — |
| `cst:allocation:confirm` | 전체 | 전체 | — | — | — |

재무(FIN)는 청구 · 입금을 등록하고 짝을 제안한다. 확정은 경영지원(ADMIN) · 임원(EXEC)이 한다. 보안 검토 정책 "만든 사람은 자기 것을 승인할 수 없다"(4-16 절)와 같은 원칙이다.

## 6. 시험

| 시험 | 내용 |
|---|---|
| `BillingMigrationTest` (내장 PG, 합성 데이터) | 표 3개 · 생성 열 · 부동소수 없음 · 권한 19건 / 청구 불변 / 입금 · 배분 INSERT 만 · 명세서 줄 멱등 / 4-eyes · 초과 배분 거부 · 부분 입금 / 역분개 1회 · VOID 조건 / 기준일 2026-10-31 aging(구간별 건수 · 금액, 기준일 뒤 입금 제외) |
| `BillingMatchGoldenTest` | Python 합성 정답 16건과 점수 일치 · aging 경계 12건 · 이름 정규화 · 발행 전 입금/금액 불일치는 자동 후보 아님 |
| `CstMigrationTest` | cst 기본 표 수 12 → 15 로 갱신 |

정답 다시 만들기: `python tools/cst_billing_golden_gen.py`.

## 7. 다음 단계

**2단계 (서비스 · API)**
- `BillingService`: 청구 발행(번호 `INV-연도-일련`) · 입금 등록(명세서 CSV/엑셀 업로드, FCD 파서 재사용) · 짝 제안(점수 계산) · 확정 · 역분개. 쓰기 진입점은 서비스 하나(ArchUnit 규칙 추가).
- API `/api/v1/cst/invoices` · `/receipts` · `/allocations:propose|confirm|reverse` · `/receivables/open-items?asOf=` · `/receivables/aging?asOf=`. 멱등 키 · If-Match · problem+json 은 공통 규칙 그대로.
- 이벤트: `cst.invoice.issued` · `cst.receipt.allocated` · `cst.invoice.settled`(전액 입금) → 포털(PTL) 표시, 수익성 MV 에 수금 열 추가.
- 영업(SAL) `sal.contract.signed` 의 기성 조건 → 청구 일정 초안.

**3단계**
- 독촉(dunning): 30일 알림 · 60일 담당자 · 90일 공문 초안. 발송은 사람이 확인한 뒤.
- 통장 출금 계정 분류: "대체"(자기 계좌 사이 이동) 규칙 먼저, 나머지는 사람이 확정. 현금 흐름 집계에서 대체 제외.
- 거래처 이름 묶음: `core.client` 중복 병합(SAL Step 3 CRM)과 `PartyName` 규칙 공유.
- 더존 전표 연동(Step 9)에서 수금 전표 동기화.

## 8. 시제품(Python) ↔ 이 저장소 대응

| 시제품 `limpst/atom_erp` | 이 저장소 | 비고 |
|---|---|---|
| `invoices` 표 · `v_open_items` · `v_aging` | `cst.invoice` · `cst.invoice_open_items(as_of)` · `cst.receivable_aging(as_of)` | 구간 A~E · 기준일 인자 같음. 시제품의 "잔액" 칸 대신 배분 합으로 계산 |
| `bank` 표(통장 23,496줄) | `cst.bank_receipt`(입금만) | 출금 · 계정 분류는 3단계 |
| `clearing` 표 · `/api/match` 점수 | `cst.receipt_allocation` · `MatchScorer` | 점수 식 정리: 금액 50/35 · 이름 bigram Dice 30 · 날짜 20/10/5 |
| `customer` · `customer_alias`(BP 묶음) | `PartyName.normalize` (+ SAL CRM 병합) | 법인 표기 제거 규칙 같음 |
| `acct_suggest`(대체 규칙) | 3단계 | 2026 미분류 15.3억 원 = 대체였다는 발견을 규칙으로 |
| `contracts` 표(잔여기성) | SAL 계약 + CST 실행예산 | 계약 금액 − 청구 합 = 미청구 잔액(2단계 조회) |
| PII 검사 · 마스킹 도구 | 이 저장소는 합성 데이터만 | 실제 자료는 넣지 않는다 |

## 9. 3단계 설계: 독촉 · 통장 계정 분류 (코드 없음, 설계만)

### 9-1. 독촉(dunning)

| 단계 | 조건(기준일 − 발행일, 미수 > 0) | 하는 일 | 누가 |
|---|---|---|---|
| L0 알림 | 31일 이상 | 담당 PM · 재무의 "내 할 일"에 표시 | 자동 |
| L1 담당자 안내 | 61일 이상 | 발주처 담당자에게 보낼 안내문 초안 생성 | 초안 자동, 발송은 재무 |
| L2 공문 | 91일 이상(aging D · E) | 공문 초안(청구 목록 · 금액 · 입금 계좌) 생성, 임원 결재 | 초안 자동, 결재 · 발송은 사람 |
| L3 보류 | 분쟁 · 하자 협의 중 | 독촉 정지(사유 필수), 해제도 기록 | 재무 등록, 경영지원 확인 |

- 표 `cst.dunning_notice(id, invoice_id, level, as_of, status DRAFT|APPROVED|SENT|CANCELLED, created_by, approved_by, sent_at)` — `(invoice_id, level)` 유일, INSERT + 상태 전이만.
- 같은 청구 · 같은 단계는 한 번만 만든다(야간 배치가 여러 번 돌아도 멱등). 전액 입금되면 남은 초안은 CANCELLED.
- 이벤트 `cst.dunning.drafted` → 내 할 일 수신함. **자동 발송은 없다**(외부 발송은 사람 확인 뒤).
- 시험: 기준일을 31 · 61 · 91일로 옮기며 단계가 한 번씩만 생기는지, 부분 입금 뒤 금액이 줄어드는지, 보류 중에는 안 생기는지.

### 9-2. 통장 출금 계정 분류

- 배경: 실제 통장 자료에서 계정이 비어 있던 2026 출금 15.3억 원이 전부 자기 계좌 사이 이동("○○은행으로 대체")이었다. 이 줄을 지출로 세면 현금 흐름이 15억 원 넘게 부풀려진다.
- 표 `cst.bank_line(id, bank_account, value_date, direction IN|OUT, amount_krw, memo_text, statement_key, account_code, classified_by, classified_at)` — 입금(`bank_receipt`)과 같은 명세서 업로드에서 만든다.
- 규칙(시행일이 있는 규칙표, FCD 룰셋 방식):
  1. 거래내용에 "대체" 또는 자기 계좌 별칭 → `TRANSFER`. **자동 확정**(뜻이 분명). 같은 날 같은 금액의 반대 방향 줄과 짝이 맞는지 검사하고, 짝이 없으면 사람에게.
  2. "사무실경비 현금 인출" ↔ 현금 장부 입금 → `TRANSFER`(이중 계산 방지).
  3. 급여 · 4대보험 · 세금 · 카드 결제 · 이자 등 키워드 → 계정 **제안**만, 확정은 사람.
- 현금 흐름 집계(월 입금 · 출금)는 `TRANSFER` 를 뺀다. 잣대가 바뀐 달은 전 · 후 값을 나란히 보여 준다.
- 시험: 대체 짝(같은 날 · 같은 금액 · 반대 방향) 일치율, 짝 없는 대체는 미확정으로 남는지, 집계에서 빠지는지.

#### 9-2 구현 (2026-11-21, V0057)
- 표 `cst.bank_line`: 업로드한 명세의 **모든 줄**(입금 · 출금) 1줄 = 1행. 입금 줄은 `receipt_id` 로 `bank_receipt` 와 이어진다. 줄 내용은 불변, `confirmed_acct · confirmed_by · confirmed_at` 세 열만 바뀐다(트리거 CST-409-034, DELETE 금지). 세 열은 함께 있거나 함께 없다(ck_bank_line_confirm).
- 제안 `BankLineClassifier`: 시제품 `classify_rules.py` RULES 25개를 순서 그대로 이식(첫 일치가 이긴다). **'대체'(자기 계좌 간 이동 · 사무실경비 현금 인출)만 자동 확정**, 나머지는 제안. 입금 줄 기본 제안은 '매출입금'. 직원 이름 매칭은 개인정보라 서버에 넣지 않는다.
- 확정 `POST /bank-lines/{id}:classify` {acct, force?, reason?} — 재무(`cst:bankline:write`). 계정명은 `GET /bank-lines/accounts` 목록 안의 것만(422). 이미 확정된 줄은 `force` + 사유 없이는 409(CST-409-035). 감사 로그에 전 · 후 계정 · 사유.
- 조회 `GET /bank-lines?from&to&unclassifiedOnly&direction` (최대 1,000줄) · `GET /bank-lines/summary?from&to` (계정 × 방향 합계) · 뷰 `cst.bank_line_monthly` (계좌 × 월 × 방향 × 계정, 미확정은 '미분류').
- 업로드 응답에 `linesStored · autoClassified · suggested · unclassified` 를 더했다. 같은 파일 재업로드는 줄 원장에도 0줄.

## 10. 웹 화면 설계 (apps/erp-web `features/cst`, 2단계 뒤)

| 화면 | 누가 | 보여 주는 것 | 쓰는 API |
|---|---|---|---|
| 미수 현황 `CstReceivablesPage` | 재무 · 경영지원 · 임원 · 감사 | 기준일 선택(기본 = 지난달 말일) → aging 막대(A~E, 건수 · 금액) + 미수 목록(발주처 · 청구 번호 · 발행일 · 합계 · 받은 돈 · 미수 · 경과일 · 구간). 표 머리 정렬 · 검색 | `GET /receivables/aging?asOf=` · `GET /receivables/open-items?asOf=` |
| 통장 대사 `CstReconcilePage` | 재무 | 왼쪽 미수 청구, 오른쪽 남은 입금. 가운데 짝 후보(점수 · 금액/이름/날짜 내역). 후보를 눌러 금액 확인 → "제안" | `GET /allocations/candidates?asOf=&minScore=` · `POST /allocations:propose` |
| 내 할 일(확정 대기) | 경영지원 · 임원 | 확인 대기 제안 목록(제안자 · 점수 · 금액). "확정" · "반려(사유)". 자기가 제안한 건은 버튼이 꺼져 있다 | `POST /allocation-proposals/{id}:confirm|reject` + 수신함 API(`BillingInboxService`, 기존 내 할 일 방식) |
| 프로젝트 청구 탭 | PM(자기 프로젝트) · 재무 | 프로젝트의 청구 목록 · 상태 · 받은 돈 | `GET /projects/{projectId}/invoices` |
| 청구 발행 | 재무 | 프로젝트 · 발주처 · 발행일 · 공급가액 · 부가세 입력. 합계는 화면이 아니라 서버 값으로 표시 | `POST /invoices` · `POST /invoices/{id}:void` |
| 입금 등록 | 재무 | 통장 명세 파일 올리기(12장) 또는 한 줄 입력 | `POST /receipts` |

화면 규칙
- 금액은 원 단위 정수로 받고 표시만 "억 원 · 소수 1자리"를 같이 보인다. 계산은 서버 값만 쓴다.
- 기준일을 바꾸면 숫자가 바뀌므로 화면 위에 "기준일 2026-10-31" 을 항상 보인다.
- 통신이 끊기면 제안 · 확정 버튼을 끈다(감리일지처럼 초안 저장은 하지 않는다 — 돈과 관련된 동작은 다시 누르게 한다).
- 시험: Vitest(기준일 변경 → 재조회, 자기 제안 건 버튼 꺼짐) · Playwright(제안 → 다른 사용자 확정 → 미수에서 사라짐).

#### 10-2 구현 추가 (2026-11-21)
- `/cst/receivables` 에 두 패널: **통장 명세 올리기**(재무, 계좌 별칭 + 파일 → 결과 KPI 읽은 줄 · 입금 줄 등록 · 출금 줄 · 자동 확정 · 사람 확인 필요, 잔액 불일치 줄 경고) · **통장 출금 계정 확정**(기준일 달 1일~기준일 · 미확정만 · OUT, 제안 계정을 그대로 또는 골라서 확정. 감사 · 임원은 읽기만).
- 새 화면 `/cst/dunning` **독촉 통지(4-eyes)**: 상태 KPI(초안 · 결재됨 · 보냄) · 상태 필터 · 재무 "초안 만들기(기준일)" · 임원/경영지원 "결재"(본인 초안이면 버튼 대신 "본인 초안") · 재무 "보냄 기록"(APPROVED 만) · "보류"(사유 2자 이상 필수) · 본문 모달. 메뉴 CST › 독촉 통지.
- Vitest: receivables 11개(업로드 · 확정 2개 추가) · dunning 6개 · 전체 20개 통과. `tsc -b` 통과.

## 11. 이벤트 계약 초안 (2단계, `cst.api.CstEvents` 에 추가)

| 이벤트 | 언제 | payload (레코드 컴포넌트) | 구독 |
|---|---|---|---|
| `cst.invoice.issued` | 청구 발행 커밋과 같은 트랜잭션(Outbox) | `invoiceId, invoiceNo, projectId, clientId, issueDate, supplyKrw, vatKrw, totalKrw` | PTL(고객 포털 "청구" 표시) · SAL(계약 기성 진행) |
| `cst.receipt.allocated` | 배분 확정 · 역분개 | `allocationId, invoiceId, receiptId, amountKrw(음수 = 역분개), reversalOf, confirmedBy` | 내 할 일(대기 건 닫기) · 감사 |
| `cst.invoice.settled` | 순 배분 합 = 청구 합계가 된 순간(1회) | `invoiceId, invoiceNo, projectId, clientId, totalKrw, settledOn(마지막 입금일)` | PTL · 수익성 MV 갱신 · HRQ/SAL 없음 |
| `cst.invoice.reopened` | 전액 입금이던 청구가 역분개로 다시 미수가 됨 | `invoiceId, invoiceNo, openKrw` | PTL · 독촉 배치(3단계) |
| `cst.invoice.voided` | VOID | `invoiceId, invoiceNo, reason` | PTL · SAL |

- aggregateType = `Invoice`, aggregateId = `invoiceId`. 금액은 문자열이 아닌 숫자(원 단위 정수)로 싣는다.
- 멱등: `cst.invoice.settled` 는 (invoiceId, 배분 id)로 한 번만. 구독 쪽은 `core.processed_event` 로 중복을 버린다.
- 개인정보: payload 에 입금자 표기(payer_text) · 계좌 별칭을 넣지 않는다. 필요한 구독자는 API 로 조회한다.
- 계약 시험: `PtlCstContractTest`(가칭)에서 발행 레코드 ↔ 구독 레코드 필드 일치를 검사한다(기존 `FcdCstContractTest` 방식).

## 12. 통장 명세 업로드 (2단계 후반 — 구현, 2026-11-21)

- 결정 변경: FCD 파서(`RowSource`)는 `fcd.domain.rules.PyFormat`(파이썬 숫자 표기 패리티)에 묶여 있어 공통 라이브러리로 빼면 FCD 골든 패리티 시험을 함께 옮겨야 한다. 그래서 CST 는 **작은 전용 파서** `cst.domain.billing.BankStatementParser`(약 390줄, 순수 도메인)를 둔다. 두 파서가 같은 일을 하는 부분(인코딩 · 매직넘버 · CSV 읽기)은 나중에 `common-ingest` 로 모으되, 그때 FCD 시험 전체를 함께 돈다.
- 읽는 것: CSV(UTF-8 · UTF-8 BOM · CP949, 구분자 쉼표 · 탭 · 세미콜론 자동) · XLSX(첫 시트, 날짜 셀 · 숫자 셀). 행 20만 · 열 64 상한(압축 폭탄 방지).
- 머리글: 앞 20줄 중 "거래일 · 입금 · 출금 · 금액 · 구분 · 거래내용 · 잔액" 비슷한 말이 가장 많은 줄. 안내 문구 · 합계 줄은 자동으로 건너뛴다. 입금 · 출금이 한 열("금액" + 부호 또는 "구분" 열)인 은행도 받는다.
- `statement_key` = `sha256(파일)[:16] + ":" + 줄번호`. 같은 파일을 다시 올리면 `registerReceipt` 가 전부 replayed 로 돌려 새 줄 0(DB 유일 제약 `ux_bank_receipt_line` 이 마지막 방어).
- 잔액 열이 있으면 줄마다 `앞 잔액 + 입금 − 출금 = 잔액` 을 확인해 어긋난 줄 번호를 응답에 넣는다(저장은 안 함).
- 1차 범위: **입금 줄만** `cst.bank_receipt` 로. 출금 줄은 세기만 하고(응답 `outflowLines`) 9-2 절 `cst.bank_line` 에서 저장 · 분류한다.
- API `POST /api/v1/cst/receipts:upload` (multipart `file` + `bankAccount` 계좌 별칭, `cst:receipt:write` = 재무). 응답: 파일 지문 · 형식 · 인코딩 · 머리글 행 · 입금/출금 줄 수 · 등록/재생 수 · 잔액 불일치 줄 · 경고.
- 개인정보: 계좌번호 원문은 받지 않는다(별칭만). 거래내용은 200자로 잘라 저장. 감사 로그에는 파일 지문 · 건수만.
- 시험: `BankStatementParserTest` 4개(CP949 CSV · BOM + 안내 3줄 + 탭 · XLSX 금액+구분 · 잘못된 머리글) · 통합 시험 업로드(등록 2 · 재업로드 replayed 2 · 권한 · 422).

한 줄 요약: 청구는 못 고치고, 입금과 짝 기록은 못 지우고, 짝은 두 사람이 확인하며, 미수는 기준일로 계산한다 — 이 네 가지를 데이터베이스가 직접 지킨다.
