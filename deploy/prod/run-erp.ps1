# =====================================================================
# atom-erp 운영 기동기 (Windows 서버용, 보안 검토 CMN-04) — run-erp.sh 와 같은 규칙.
#  · Spring 프로필을 항상 prod 로 고정(환경 파일 · 호출자 값 무시) + 운영 배포 표식 ATOM_DEPLOY_ENVIRONMENT=prod
#  · 비밀값은 이 파일에 쓰지 않는다. 환경 파일(기본 C:\atom-erp\erp.env, 양식 deploy\prod\erp.env.example)에서 읽는다
# 사용: $env:ATOM_ERP_HOME = 'C:\atom-erp'; .\deploy\prod\run-erp.ps1
# =====================================================================
$ErrorActionPreference = 'Stop'

$envFile = if ($env:ATOM_ENV_FILE) { $env:ATOM_ENV_FILE } else { 'C:\atom-erp\erp.env' }
if (Test-Path $envFile) {
    foreach ($line in Get-Content -Encoding UTF8 $envFile) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#') -or -not $t.Contains('=')) { continue }
        $i = $t.IndexOf('=')
        [Environment]::SetEnvironmentVariable($t.Substring(0, $i).Trim(), $t.Substring($i + 1).Trim(), 'Process')
    }
}

# 프로필 고정(CMN-04) — 환경 파일에 다른 값이 있어도 덮어쓴다
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:ATOM_DEPLOY_ENVIRONMENT = 'prod'

foreach ($a in $args) {
    if ("$a" -like '*spring.profiles*') {
        Write-Error "run-erp.ps1: spring.profiles 인자는 지정할 수 없습니다(prod 고정, CMN-04): $a"
        exit 64
    }
}

$home_ = if ($env:ATOM_ERP_HOME) { $env:ATOM_ERP_HOME } else { 'C:\atom-erp' }
$bin = Join-Path $home_ 'bin\erp-server.bat'
if ($env:ATOM_ERP_DRY_RUN -eq '1') {
    Write-Output "SPRING_PROFILES_ACTIVE=$($env:SPRING_PROFILES_ACTIVE) ATOM_DEPLOY_ENVIRONMENT=$($env:ATOM_DEPLOY_ENVIRONMENT) $bin --spring.profiles.active=prod $args"
    exit 0
}
& $bin --spring.profiles.active=prod @args
exit $LASTEXITCODE
