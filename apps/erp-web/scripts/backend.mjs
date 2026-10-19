// 로컬 E2E · 개발용 백엔드 실행기: 내장 PostgreSQL + local-noauth + 합성 시드 (apps/erp-server DevServer).
// JAVA_HOME 은 이 프로세스의 자식에게만 넘긴다(시스템 설정 변경 없음). 기본값: PyCharm 번들 JBR.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../../..');
const server = resolve(repo, 'apps/erp-server');
const javaHome =
  process.env.ATOM_JAVA_HOME || process.env.JAVA_HOME || 'C:/Program Files/JetBrains/PyCharm 2026.1.2/jbr';
const env = { ...process.env, JAVA_HOME: javaHome };
const win = process.platform === 'win32';

if (!process.env.ATOM_SKIP_GRADLE) {
  const r = spawnSync(resolve(repo, win ? 'gradlew.bat' : 'gradlew'), [':apps:erp-server:devServerArgs', '-q'], {
    cwd: repo, env, stdio: 'inherit', shell: win,
  });
  if (r.status !== 0) process.exit(r.status ?? 1);
}
const args = resolve(server, 'build/dev-server.args');
if (!existsSync(args)) {
  console.error('dev-server.args 없음 — gradlew :apps:erp-server:devServerArgs 를 먼저 실행하세요');
  process.exit(1);
}
const java = resolve(javaHome, 'bin', win ? 'java.exe' : 'java');
const child = spawn(java, [`@${args}`], { cwd: server, env, stdio: 'inherit' });
const stop = () => child.kill();
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
process.on('exit', stop);
child.on('exit', (code) => process.exit(code ?? 0));
