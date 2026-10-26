#!/usr/bin/env sh
# =====================================================================
# atom-erp 운영 기동기 (보안 검토 CMN-04) — 운영 서버는 반드시 이 스크립트로 기동한다.
#  · Spring 프로필을 항상 prod 로 고정한다(환경 파일 · 호출자 값 무시). prod 프로필이어야 운영 가드가 켜진다:
#    헤더 인증 금지(CMN-04) · audience 필수(CMN-06) · 증거 저장소 Object Lock(CMN-07) · DB 기본 비밀번호 금지(CMN-13)
#  · 운영 배포 표식 ATOM_DEPLOY_ENVIRONMENT=prod 를 함께 넣는다 — 누가 프로필을 바꿔 기동하면 ProductionGuards 가 기동 거부
#  · 비밀값은 이 파일에 쓰지 않는다. 환경 파일(기본 /etc/atom-erp/erp.env, 양식 deploy/prod/erp.env.example)에서 읽는다
# 사용: ATOM_ERP_HOME=/opt/atom-erp deploy/prod/run-erp.sh   (installDist 결과: $ATOM_ERP_HOME/bin/erp-server)
# =====================================================================
set -eu

ENV_FILE="${ATOM_ENV_FILE:-/etc/atom-erp/erp.env}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

# 프로필 고정(CMN-04) — 환경 파일에 다른 값이 있어도 덮어쓴다
export SPRING_PROFILES_ACTIVE=prod
export ATOM_DEPLOY_ENVIRONMENT=prod

for a in "$@"; do
  case "$a" in
    *spring.profiles*) echo "run-erp.sh: spring.profiles 인자는 지정할 수 없습니다(prod 고정, CMN-04): $a" >&2; exit 64 ;;
  esac
done

BIN="${ATOM_ERP_HOME:-/opt/atom-erp}/bin/erp-server"
if [ "${ATOM_ERP_DRY_RUN:-}" = "1" ]; then
  echo "SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE ATOM_DEPLOY_ENVIRONMENT=$ATOM_DEPLOY_ENVIRONMENT $BIN --spring.profiles.active=prod $*"
  exit 0
fi
exec "$BIN" --spring.profiles.active=prod "$@"
