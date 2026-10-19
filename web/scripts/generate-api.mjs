import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';

const contracts = [
  ['query-v1', '../api/openapi/query-v1.yaml'],
  ['control-plane-v1', '../api/openapi/control-plane-v1.yaml'],
  ['alerts-v1', '../api/openapi/alerts-v1.yaml'],
];

const checkOnly = process.argv.includes('--check');
let failed = false;

for (const [name, source] of contracts) {
  if (!existsSync(source)) {
    console.warn(`skip ${name}: ${source} does not exist`);
    continue;
  }
  const args = [source, '-o', `src/api/generated/${name}.d.ts`];
  if (checkOnly) args.push('--check');
  const result = spawnSync('npx', ['openapi-typescript', ...args], { stdio: 'inherit' });
  if (result.status !== 0) failed = true;
}

process.exit(failed ? 1 : 0);
