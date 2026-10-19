# Keycloak realm 설정 — 배포 전 확인표

## 쉬운 말 한 장 요약

- 로그인 서버(Keycloak)가 주는 **출입증(토큰)** 안에는 "이 사람은 어느 고객사 사람인가(`cid`)" 같은 정보가 들어 있습니다.
- 이 값을 **사용자 본인이 고칠 수 있으면**, 다른 회사 사람인 척할 수 있습니다. 그래서 **관리자만 고칠 수 있게** 설정해야 합니다.
- 출입증에는 "이 출입증은 ERP 용"이라는 표시(`aud = atom-erp-api`)가 붙습니다. ERP 서버는 이 표시가 없는 출입증을 받지 않습니다.
- 이 폴더의 파일에는 **진짜 비밀번호 · 비밀값이 없습니다.** `${…}` 자리표시자만 있고, 배포할 때 채웁니다.

## 1. 토큰 audience (보안 검토 CMN-06)

| 항목 | 값 |
|---|---|
| realm 매퍼 | 기본 client scope `atom-claims`(직원) · `atom-portal-claims`(포털)에 `erp-api-audience`(`oidc-audience-mapper`, `included.custom.audience = atom-erp-api`) |
| ERP 설정 | `atom.security.audiences` (환경변수 `ATOM_SECURITY_AUDIENCES`, 기본 `atom-erp-api`) |
| 동작 | JWT `aud` 에 허용 audience 가 하나도 없으면 401. OIDC 모드에서 목록이 비어 있으면 **기동 실패** |
| 시험 | `KeycloakRealmExportTest.audienceMapper` · `ProductionGuardsAndAudienceTest` |

audience 이름을 바꾸면 realm 매퍼와 `ATOM_SECURITY_AUDIENCES` 를 **함께** 바꿉니다.

## 2. 포털 `cid` 클레임 — 반드시 관리자 전용 속성 (보안 검토 CMN-06)

`atom-portal-claims` 의 `client_org_id` 매퍼는 사용자 속성 **`client_id`** 를 토큰 클레임 **`cid`**(고객사 `core.client.id`)로 넣습니다.
ERP 포털은 이 `cid` 하나로 고객사 데이터(RLS)를 나눕니다. 따라서:

1. **User Profile(선언형 사용자 프로필)** 에서 `client_id` 속성을 다음처럼 둡니다.
   - `permissions.view = ["admin"]`, `permissions.edit = ["admin"]` — 사용자 본인(`user`)은 보기 · 고치기 모두 불가
   - 계정 콘솔 · 등록 화면에 표시하지 않음(`annotations` 없음, `required` 없음)
2. `unmanagedAttributePolicy` 는 `ENABLED` 로 두지 않습니다(정의되지 않은 속성을 사용자가 만들 수 없게). `ADMIN_EDIT` 또는 미설정.
3. 자가 등록(`registrationAllowed`)은 `false` 유지 — 고객 계정은 관리자가 만들고 `client_id` 를 넣습니다.
4. 배포 후 확인: 고객 계정으로 계정 콘솔(`/realms/atom-portal/account`)에 들어가 `client_id` 가 **보이지도 바뀌지도 않는지** 봅니다.
   관리 REST(`PUT /admin/realms/atom-portal/users/{id}`)는 realm 관리자 토큰으로만 바뀌는지 확인합니다.

직원 realm 의 `eid`(`employee_id` 속성)도 같은 규칙(관리자 전용)을 적용합니다.

> realm export 에 User Profile 구성을 넣지 않은 이유: Keycloak 버전마다 형식(`components` → `UserProfileProvider` 의
> `kc.user.profile.config`)이 달라, 운영 버전에서 관리 콘솔로 설정 후 export 하는 것이 안전합니다. 설정 후 이 저장소의 export 를
> 갱신하면 `KeycloakRealmExportTest` 가 클레임 규약(roles · eid / roles · cid)을 계속 검사합니다.

## 3. 그 밖의 배포 확인

- 클라이언트 비밀값은 `${…}` 자리표시자 — import 할 때 실제 값으로 치환(저장소에 커밋 금지).
- `redirectUris` 의 `http://localhost:*` 는 개발용입니다. 운영 realm 에서는 지웁니다(보안 검토 CMN-06 후속).
- ERP 를 `prod`/`production` 프로필로 띄우면 `atom.security.enabled=false`(헤더 인증)와 임시 폴더 증거 저장소는 **기동 거부**됩니다(CMN-04 · CMN-07).
