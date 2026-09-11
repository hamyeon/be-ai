#!/usr/bin/env node
// k6 --summary-export JSON 파일 여러 개를 읽어 요청한 비교표를 한 번에 뽑아낸다.
// Table A: VU | p50 | p95 | p99 | success | business reject | system fail | lockFail(40909) | unrelated GET p95
// Table B: 같은 run_tag의 observability-*.json/lockhold-*.json/invariant-*.json/rootcause-*.md를
//          찾아 Hikari/InnoDB/MySQL 1205·1213/lock hold time/correctness/rootcause 유무를 병합.
//          150 VU 실험부터 생긴 파일들이라, 옛 run(20/50/100/200)에는 lockhold-*.json이나
//          1205/1213 델타가 없을 수 있다 - 없으면 그냥 N/A로 표시하고 추정하지 않는다.
//
// 사용법: node summarize.js results/summary-*.json
// (run-stages.sh가 전체 실행 뒤 자동으로 호출한다. 개별 summary 파일만 다시 보고 싶을 때도 쓸 수 있다.)
const fs = require('fs');
const path = require('path');

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error('사용법: node summarize.js <summary1.json> [summary2.json ...]');
  process.exit(1);
}

// k6 버전에 따라 --summary-export 구조가 다르다 - 구버전은 metrics[name].values[key],
// k6 v2.2.0(이 프로젝트가 설치한 버전)은 metrics[name][key]를 직접 쓴다(values 래퍼 없음).
// 둘 다 지원하도록 values가 있으면 그걸 먼저 보고, 없으면 바로 그 객체에서 찾는다.
function metricValue(metrics, name, key, fallback) {
  const m = metrics[name];
  if (!m) return fallback;
  const source = m.values ? m.values : m;
  return source[key] === undefined ? fallback : source[key];
}

function vuFromFilename(file) {
  const base = path.basename(file, '.json').replace(/^summary-/, '');
  const match = base.match(/vu(\d+)/i);
  return match ? match[1] : base; // "vu8-run1" -> "8", 그 외("smoke" 등)는 그대로 표시
}

function runTagFromFilename(file) {
  return path.basename(file, '.json').replace(/^summary-/, ''); // "vu150-run1"
}

function fmt(n) {
  if (n === null || n === undefined || Number.isNaN(n)) return 'n/a';
  return Number(n).toFixed(1);
}

function naIfMissing(v) {
  return v === null || v === undefined ? 'N/A' : v;
}

function readJsonIfExists(p) {
  if (!fs.existsSync(p)) return null;
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch (e) {
    return null;
  }
}

const rows = [];
for (const file of files) {
  let data;
  try {
    data = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    console.error(`[skip] ${file}: ${e.message}`);
    continue;
  }
  const metrics = data.metrics || {};

  const success = metricValue(metrics, 'bid_success', 'count', 0);
  const businessReject = metricValue(metrics, 'bid_business_rejection', 'count', 0);
  const systemFail = metricValue(metrics, 'bid_system_failure', 'count', 0);
  const lockFail = metricValue(metrics, 'bid_lock_failure', 'count', 0);
  const total = success + businessReject + systemFail;

  const dir = path.dirname(file);
  const runTag = runTagFromFilename(file);
  const obs = readJsonIfExists(path.join(dir, `observability-${runTag}.json`));
  const lockhold = readJsonIfExists(path.join(dir, `lockhold-${runTag}.json`));
  const invariant = readJsonIfExists(path.join(dir, `invariant-${runTag}.json`));
  const rootcausePath = path.join(dir, `rootcause-${runTag}.md`);
  const hasRootcause = fs.existsSync(rootcausePath);

  rows.push({
    file: path.basename(file),
    runTag,
    vu: vuFromFilename(file),
    p50: metricValue(metrics, 'bid_duration', 'med', NaN),
    p95: metricValue(metrics, 'bid_duration', 'p(95)', NaN),
    p99: metricValue(metrics, 'bid_duration', 'p(99)', NaN),
    total,
    success,
    successRate: total > 0 ? ((success / total) * 100).toFixed(1) + '%' : 'n/a',
    businessReject,
    businessRejectRate: total > 0 ? ((businessReject / total) * 100).toFixed(1) + '%' : 'n/a',
    systemFail,
    systemFailRate: total > 0 ? ((systemFail / total) * 100).toFixed(1) + '%' : 'n/a',
    lockFail,
    unrelatedP95: metricValue(metrics, 'unrelated_get_duration', 'p(95)', NaN),

    hikariActiveMax: obs ? naIfMissing(obs.hikariActiveMax) : 'N/A',
    hikariPendingMax: obs ? naIfMissing(obs.hikariPendingMax) : 'N/A',
    hikariConfiguredMax: obs ? naIfMissing(obs.hikariConfiguredMax) : 'N/A',
    innodbRowLockWaitsDelta: obs ? naIfMissing(obs.innodbRowLockWaitsDelta) : 'N/A',
    lockWaitTimeout1205: obs ? naIfMissing(obs.mysqlLockWaitTimeout1205Delta) : 'N/A',
    deadlock1213: obs ? naIfMissing(obs.mysqlDeadlock1213Delta) : 'N/A',

    lockHoldAvgMs: lockhold ? naIfMissing(lockhold.avgMs) : 'N/A',
    lockHoldP95Ms: lockhold ? naIfMissing(lockhold.p95Ms) : 'N/A',
    lockHoldSamples: lockhold ? lockhold.sampleCount : 'N/A',
    lockHoldUncorrelated: lockhold ? lockhold.uncorrelatedCount : 'N/A',

    bidAfterEndAt: invariant ? naIfMissing(invariant.bidsAfterEndAt) : 'N/A',
    priceReversal: invariant ? naIfMissing(invariant.priceReversalCount) : 'N/A',
    violations: invariant ? (invariant.violations && invariant.violations.length ? invariant.violations.join(';') : 'NONE') : 'N/A',

    rootcause: hasRootcause ? `rootcause-${runTag}.md` : 'N/A',
  });
}

const headerA = ['file', 'VU', 'p50(ms)', 'p95(ms)', 'p99(ms)', 'total', 'success', 'successRate',
  'bizReject', 'bizRejectRate', 'sysFail', 'sysFailRate', 'lockFail(40909)', 'unrelatedGET_p95(ms)'];
console.log('## Table A - k6 요청 결과');
console.log(headerA.join(' | '));
console.log(headerA.map(() => '---').join(' | '));
for (const r of rows) {
  console.log([
    r.file, r.vu, fmt(r.p50), fmt(r.p95), fmt(r.p99), r.total, r.success, r.successRate,
    r.businessReject, r.businessRejectRate, r.systemFail, r.systemFailRate, r.lockFail, fmt(r.unrelatedP95),
  ].join(' | '));
}

console.log();
console.log('## Table B - Hikari/InnoDB/MySQL 1205·1213/lock hold time/correctness/rootcause');
const headerB = ['runTag', 'hikariActive_max', 'hikariPending_max', 'hikariConfigured_max',
  'innodbRowLockWaits_delta', 'mysql1205(lockWaitTimeout)_delta', 'mysql1213(deadlock)_delta',
  'lockHold_avgMs(n,uncorrelated)', 'lockHold_p95Ms', 'bidsAfterEndAt', 'priceReversal', 'violations', 'rootcause'];
console.log(headerB.join(' | '));
console.log(headerB.map(() => '---').join(' | '));
for (const r of rows) {
  const holdCell = r.lockHoldAvgMs === 'N/A'
    ? 'N/A'
    : `${fmt(r.lockHoldAvgMs)}(n=${r.lockHoldSamples},uncorrelated=${r.lockHoldUncorrelated})`;
  console.log([
    r.runTag, r.hikariActiveMax, r.hikariPendingMax, r.hikariConfiguredMax,
    r.innodbRowLockWaitsDelta, r.lockWaitTimeout1205, r.deadlock1213,
    holdCell, r.lockHoldP95Ms === 'N/A' ? 'N/A' : fmt(r.lockHoldP95Ms),
    r.bidAfterEndAt, r.priceReversal, r.violations, r.rootcause,
  ].join(' | '));
}
