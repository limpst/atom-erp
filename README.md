# 에이톰 안전진단 ERP — 공통 플랫폼 (Phase 0, 첫 단계)

## 쉬운 말 한 장 요약

- 이 저장소는 회사 ERP(회사 일을 한곳에서 관리하는 프로그램)의 **바닥 공사**입니다. 집으로 치면 기초·배관·전기 배선을 먼저 깐 상태예요.
- 지금 들어 있는 것은 다섯 가지입니다.
  1. **데이터 창고 설계도**(`V1__core.sql`): 고객·프로젝트·직원 표와, 기록을 지울 수 없게 막는 장치.
  2. **우편함(Outbox)**: "프로젝트가 생겼어요" 같은 소식을 업무 저장과 **같이** 저장했다가, 배달부(Relay)가 나중에 확실히 전달합니다. 배달이 실패하면 10초→20초→40초→80초 기다렸다 다시 시도하고, 5번 실패하면 "반송함"(DLQ)에 넣습니다.
  3. **중복 클릭 방지**(Idempotency-Key): 현장 태블릿이 같은 요청을 여러 번 보내도 한 번만 처리하고, 두 번째부터는 "이미 처리했어요"(`Idempotent-Replayed: true`)라고 같은 답을 돌려줍니다.
  4. **출입증 검사**(역할·권한): 12가지 역할(임원, 영업, 기술자, 재무, 감사 등)마다 할 수 있는 일을 정하고, 기술자는 **자기가 투입된 프로젝트만** 볼 수 있습니다.
  5. **알기 쉬운 오류 쪽지**(problem+json): 오류마다 `CORE-409-010` 같은 번호가 붙어 원인을 바로 찾을 수 있습니다.
- 모든 기능은 자동 시험 **89개**(88개 통과, 1개는 PostGIS 서버가 있을 때만 실행)로 확인했습니다. 시험 데이터는 전부 **가짜(합성) 데이터**입니다.

> 설계 근거: Notion「00. 공통 아키텍처 · 설계 표준 v0.3」, 「00-IMPL. 공통 플랫폼 구현 보고서」, 「에이톰 플랫폼 모듈 라이브러리(ATOM-LIB-001)」.

### 용어 정리

| 용어 | 쉬운 뜻 |
|---|---|
| 모듈형 모놀리스 | 프로그램은 하나지만 안에 칸막이(모듈)를 쳐서 서로 함부로 못 건드리게 한 구조 |
| Spring Modulith | 칸막이를 어겼는지 자동으로 검사해 주는 도구 |
| Flyway | 데이터베이스 설계도를 버전 순서대로 적용해 주는 도구 |
| Outbox / Relay | 보낼 소식을 먼저 우편함에 넣고(Outbox), 배달부(Relay)가 꺼내 전달 |
| DLQ (outbox_dead) | 여러 번 배달 실패한 소식을 모아 두는 반송함 |
| 멱등(Idempotency) | 같은 요청을 여러 번 보내도 결과가 한 번 보낸 것과 같음 |
| ETag / If-Match | 문서 버전 번호. 남이 먼저 고쳤으면 "버전이 달라요"(412)라고 막음 |
| 해시 체인 | 기록마다 앞 기록의 지문을 이어 붙여, 중간을 몰래 고치면 들통나게 하는 방법 |
| UUID v7 | 시간 순서대로 정렬되는 고유 번호(아이디) |
| RFC 7807 problem+json | 오류를 정해진 JSON 모양으로 알려주는 국제 표준 |

---

## 1. 모듈 구성

```mermaid
flowchart BT
  subgraph COMMON["공통 서비스 라이브러리 com.atomeng.common.*"]
    CC["common-core<br>UUID v7 · BaseEntity · 오류코드 · CurrentActor"]
    CW["common-web<br>problem+json · Idempotency-Key 필터 · If-Match"]
    CI["common-identity<br>역할 12종 · OIDC 리소스서버 · @RequirePermission"]
    CE["common-events<br>OutboxPublisher · OutboxRelay · ProcessedEvents"]
    CA["common-audit<br>감사 로그 해시 체인"]
    CT["common-testing<br>내장 PostgreSQL 16"]
  end
  subgraph MODS["업무 모듈 com.atomeng.erp.{mod}"]
    CORE["core<br>V1__core.sql · Client · Project<br>api: ProjectCreatedEvent"]
    NEXT["fac · fld · rpt · qar … (예정)"]
  end
  APP["apps/erp-server<br>Spring Boot 조립 · ModularityTests"]
  CW --> CC
  CI --> CC
  CI --> CW
  CE --> CC
  CA --> CC
  CORE --> CW & CI & CE & CA
  NEXT -. "core::api 만" .-> CORE
  APP --> CORE
```

- 공통 라이브러리는 업무 모듈(`com.atomeng.erp..`)을 **절대 import 하지 않습니다**(ArchUnit 규칙 R1).
- 업무 모듈끼리는 `{mod}::api` 명명 인터페이스로만 접근합니다(Spring Modulith `verify()`).

```
atom-erp/
├─ common/common-{core,web,identity,events,audit,testing}
├─ modules/core/                 # com.atomeng.erp.core
│  └─ src/main/resources/db/migration/core/V1__core.sql
│     └─ db/migration-ext/V1_1__core_postgis_ext.sql   (프로필 postgis)
└─ apps/erp-server/              # com.atomeng.erp.ErpServerApplication
```

## 2. 빌드 방법 (Windows, 시스템 설정 변경 없이)

시스템에 Java/Gradle/Docker/PostgreSQL 이 없어도 됩니다. PyCharm 에 들어 있는 JBR(JDK 25)을 **명령 한 번에만** 지정합니다.

```bash
# Git Bash
cd C:/Users/atomS/atom-erp
JAVA_HOME="/c/Program Files/JetBrains/PyCharm 2026.1.2/jbr" ./gradlew build
```

```powershell
# PowerShell (현재 창에서만 유효)
$env:JAVA_HOME = "C:\Program Files\JetBrains\PyCharm 2026.1.2\jbr"; .\gradlew.bat build
```

- Gradle 9.7.1 은 wrapper 가 자동으로 내려받습니다.
- 바이트코드는 `--release 21`(Java 21)로 만듭니다. JDK 21 이 있는 CI 에서는 toolchain 21 로 바꿔도 됩니다.
- 통합 테스트는 **zonky embedded-postgres**(PostgreSQL 16.15 바이너리, Maven Central)로 Docker 없이 실행됩니다.
- 모듈 문서(PlantUML)는 `apps/erp-server/build/spring-modulith-docs/` 에 생성됩니다.

선택 실행:

```bash
./gradlew :modules:core:test          # 마이그레이션 테스트
./gradlew :apps:erp-server:test --tests '*ModularityTests*'
ATOM_PG_POSTGIS_URL='jdbc:postgresql://host:5432/db?user=..&password=..' ./gradlew :modules:core:test  # PostGIS 확장 테스트(TC-CORE-008)
```

로컬 실행(실제 PostgreSQL 필요, Keycloak 없이 헤더 인증):

```bash
PG_HOST=localhost PG_APP_USER=postgres PG_APP_PASSWORD=... \
  ./gradlew :apps:erp-server:run --args='--spring.profiles.active=local-noauth'
# 요청 예: -H 'X-Actor-Id: <uuid>' -H 'X-Roles: SALES' -H 'Idempotency-Key: <uuid>'
```

## 3. 구현 내용 ↔ 설계 대응

| 설계 | 구현 위치 | 핵심 |
|---|---|---|
| 7.2 core 스키마·공통 6컬럼 | `V1__core.sql`, `BaseEntity` | client·employee·project·project_member·code·attachment·hash_anchor·audit_log(월 파티션)·doc_sequence |
| UUID v7 | `UuidV7`(Java), `core.uuid_generate_v7()`(SQL) | 같은 ms 단조 증가, 시계 역행 보호 |
| 10.2/10.3 Outbox | `OutboxPublisher`(MANDATORY), `OutboxRelay` | `FOR UPDATE SKIP LOCKED`, 이벤트 1건=트랜잭션 1개, 백오프 5·2ⁿ초(10/20/40/80), 5회째 `outbox_dead`, `requeueDead()` |
| 컨슈머 멱등 | `ProcessedEvents`, `@IdempotentListener` | `INSERT … ON CONFLICT DO NOTHING`, 실패 시 함께 롤백 |
| 9.4 멱등 키 | `IdempotencyFilter`, `IdempotencyStore` | PK (actor_id,key), CORE-428-001 / CORE-409-010 / CORE-422-009, `Idempotent-Replayed`, 5xx 미저장, 24시간 보관·정리 배치 |
| 8장 RBAC | `Role`(12종), `JwtAuthConverter`, `@RequirePermission`, `core.role_permission` | EMPLOYEE·FIN·AUDITOR 포함, PROJECT/CLIENT 행 수준 권한(CORE-403-002) |
| 8.1 OIDC | `IdentityAutoConfiguration` | 다중 issuer(atom-internal/atom-portal), issuer 메타데이터 지연 조회, 테스트·로컬은 헤더 스텁 |
| 9.2 오류 모델 | `GlobalProblemHandler`, `ProblemFactory` | 모든 오류 problem+json + `code` + `traceId`, ko/en title |
| 9.1 동시성 | `IfMatch`, JPA `@Version` | ETag `"v{n}"`, If-Match 불일치·경합 → 412 CORE-412-001 |
| NFR-CORE-07 감사 | `AuditLog`, `core.audit_append()` | 해시 체인 + append-only 트리거 + `audit_verify_chain()` |
| 4장 모듈 경계 | `ModularityTests`, `ArchitectureRulesTest` | verify() + 위반 픽스처 음성 테스트 |

## 4. 테스트 ↔ TC-CORE

| TC | 시나리오 | 테스트 |
|---|---|---|
| TC-CORE-001 | ENG 가 담당 외 프로젝트 조회 → 403 CORE-403-002, 감사 로그 없음 | `AuthorizationIntegrationTest` |
| TC-CORE-002 | 같은 Idempotency-Key 로 고객 생성 3회 → 1건, 2·3회차 Replayed | `IdempotencyIntegrationTest` |
| TC-CORE-003 | 두 사용자 동시 수정 → 200 / 412 | `OptimisticLockIntegrationTest` (순차·동시 2종) |
| TC-CORE-004 | 생성 트랜잭션 예외 → 프로젝트·outbox 모두 롤백 | `OutboxIntegrationTest` |
| TC-CORE-005 | Relay 강제 종료 후 재기동 → 전량 발행, 중복 소비 0 | `OutboxIntegrationTest` |
| TC-CORE-007 | attachment.sha256 직접 UPDATE → 트리거 예외 | `CoreMigrationTest` |
| TC-CORE-008 | 규칙 기간 중복 ACTIVE → EXCLUDE 위반 | `PostgisExtMigrationTest` (PostGIS DB 있을 때만) |

미대상(후속 Step): TC-CORE-006·009(첨부·MinIO, Step 8·11), 010(PII 감사, Step 6), 011(AIP, Step 12), 012(PITR, Step 15).

## 5. 설계와 다른 점 (의도적 편차)

1. **Redis 대신 DB 잠금**: 멱등 키 "처리 중" 잠금을 Redis `SET NX 30s` 대신 `idempotency_key` 행(response_code NULL)으로 구현. 30초 지난 잠금은 다음 요청이 이어받음. Redis 어댑터는 Step 2 로컬 스택 후 추가.
2. **PostGIS·pg_trgm·btree_gist 분리**: 내장 PostgreSQL 에 확장이 없어 `client.geom`, `attachment.captured_geom`, 트라이그램 인덱스, `rule_set`(EXCLUDE) 을 `db/migration-ext`(프로필 `postgis`)로 분리.
3. **마이그레이션 1개 파일**: 설계의 V1_0_0~V1_0_6 대신 `V1__core.sql` 하나(요청 사양). 다음부터 `V1_1`, `V1_2` … 로 증분.
4. **`audit_log.module`, `attachment.owner_module` 을 varchar(4)** 로 확장('CORE' 수용).
5. **audit_log 해시 체인 컬럼 추가**(`prev_hash`, `row_hash`) — 설계 7.2 에는 없음(ATOM-LIB-001 C2 반영). 기본 파티션(`audit_log_default`)도 안전망으로 추가.
6. **Relay 리더 선출(ShedLock) 미구현**: `SKIP LOCKED` 로 다중 인스턴스도 안전하지만 중복 폴링 비용이 있음 → Step 15.
7. **Spring Boot Gradle 플러그인 미사용**(BOM 만 사용) — Gradle 9.7 호환성 위험 회피. 실행은 `application` 플러그인. 배포용 bootJar 는 Step 14 에서.
8. **convention plugin(build-logic) 대신 루트 `subprojects` 블록**.
9. 감사 로그는 **동기 기록**(업무 트랜잭션 참여). 비동기·파일 폴백·PII 마스킹·`@Audited` AOP 는 Step 6.
10. JDK 21 툴체인이 없어 **JDK 25 로 `--release 21` 컴파일·테스트**.

## 6. 다음 단계

- Step 2: docker compose(PostGIS·Keycloak·Redis·MinIO) → PostGIS 프로필 테스트 상시화, Redis 멱등 잠금
- Step 4 마무리: Keycloak realm export, 권한 매트릭스 CSV 전수 테스트(120건), ProjectScope Redis 캐시 + 무효화 이벤트
- Step 5 마무리: 커서 페이징, springdoc OpenAPI 스냅샷·diff
- Step 6: `@Audited` AOP, PII 마스킹, VIEW_PII(TC-CORE-010), 월 파티션 배치
- Step 7 마무리: 이벤트 JSON Schema(`contracts/events`), `outbox_pending` 게이지, ShedLock
- Step 8: 첨부 presign/complete·해시 체인(TC-CORE-006/009)
