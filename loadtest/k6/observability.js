#!/usr/bin/env node
// run-stages.sh가 매 run(stage x repetition)마다 호출하는 부가 관측 처리기.
// 두 개의 독립된 서브커맨드만 담당한다 - 둘 다 "측정 못한/상관관계 불확실한 값은 추정하지
// 않고 N/A·uncorrelated로 남긴다"는 원칙을 지킨다.
//
//   node observability.js lockhold <raw.tsv> <out.json> <runTag>
//     performance_schema.events_statements_history_long에서 뽑은
//     "FOR UPDATE SELECT의 TIMER_END \t 같은 THREAD_ID의 다음 COMMIT/ROLLBACK TIMER_END(또는 NULL)"
//     TSV를 읽어 lock hold time(ms) raw sample 배열 + 요약 통계를 JSON으로 남긴다.
//     TIMER_* 값은 피코초 단위 BIGINT라 Number로 바로 빼면 안전정수 범위를 넘어설 수 있다 -
//     BigInt로 빼고, 그 차이(hold time)만 Number로 변환한다(hold time 자체는 ms 단위라 안전).
//
//   node observability.js rootcause <slicedBackendLog> <out.md> <runTag> <systemFailureCount>
//     이번 run 구간만 잘라낸 backend-live.log에서 printStackTrace() 출력(=GlobalExceptionHandler의
//     catch-all Exception 핸들러가 남기는 것 - 40909(PessimisticLockingFailureException)는 어떤
//     로그도 남기지 않는다는 걸 소스에서 이미 확인했다)로 보이는 블록만 뽑아 예외 클래스별로
//     묶고 첫 샘플 스택트레이스를 남긴다. k6가 센 system failure 수와 로그에서 찾은 예외 발생
//     수를 그냥 나란히 적을 뿐, 요청 단위로 1:1 매칭한다고 주장하지 않는다(시간대만 겹칠 뿐
//     정확한 상관관계가 아님을 결과에 명시).
const fs = require('fs');

// run-stages.sh의 lockhold_raw_dump()가 주는 TSV: THREAD_ID \t EVENT_ID \t TIMER_END \t kind
// (kind는 'LOCK' 또는 'END') - MySQL 쪽에서는 평평하게 스캔만 하고(상관 서브쿼리 없음),
// THREAD_ID 안에서 EVENT_ID 순서로 "LOCK 다음 첫 END"를 짝짓는 실제 페어링은 여기서 O(n)으로
// 한다. 같은 THREAD_ID에 LOCK이 연달아 오면(END를 못 찾고 다음 LOCK이 먼저 옴) 이전 LOCK은
// uncorrelated로 버리고 새 LOCK만 pending으로 남긴다 - 추정하지 않는다.
function lockhold(tsvPath, outPath, runTag) {
  let raw;
  try {
    raw = fs.readFileSync(tsvPath, 'utf8');
  } catch (e) {
    raw = '';
  }
  const rows = raw
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => l.split('\t'))
    .filter((p) => p.length === 4);

  const samplesMs = [];
  let uncorrelated = 0;
  let parseErrors = 0;
  const pendingByThread = new Map(); // threadId -> BigInt lockEnd

  for (const [threadId, , timerEndRaw, kind] of rows) {
    let timerEnd;
    try {
      timerEnd = BigInt(timerEndRaw);
    } catch (e) {
      parseErrors++;
      continue;
    }
    if (kind === 'LOCK') {
      if (pendingByThread.has(threadId)) {
        // 이전 LOCK이 END를 못 만나고 다음 LOCK이 옴 - 이전 건 추정하지 않고 버린다.
        uncorrelated++;
      }
      pendingByThread.set(threadId, timerEnd);
    } else if (kind === 'END') {
      const lockEnd = pendingByThread.get(threadId);
      if (lockEnd === undefined) {
        // 대응하는 LOCK이 없는 COMMIT/ROLLBACK(예: read-only 트랜잭션의 커밋) - 무시.
        continue;
      }
      pendingByThread.delete(threadId);
      if (timerEnd < lockEnd) {
        uncorrelated++;
        continue;
      }
      const ms = Number(timerEnd - lockEnd) / 1e9;
      samplesMs.push(ms);
    }
  }
  // run 종료 시점까지 END를 못 만난 pending LOCK들 - 추정하지 않고 uncorrelated로만 센다.
  uncorrelated += pendingByThread.size;

  samplesMs.sort((a, b) => a - b);
  const n = samplesMs.length;
  const pct = (p) => (n === 0 ? null : samplesMs[Math.min(n - 1, Math.floor((p / 100) * n))]);
  const avg = n === 0 ? null : samplesMs.reduce((s, v) => s + v, 0) / n;

  const out = {
    runTag,
    sampleCount: n,
    uncorrelatedCount: uncorrelated,
    parseErrorCount: parseErrors,
    avgMs: avg,
    p50Ms: pct(50),
    p95Ms: pct(95),
    p99Ms: pct(99),
    maxMs: n === 0 ? null : samplesMs[n - 1],
    samplesMs,
    note: n === 0
      ? 'N/A - performance_schema.events_statements_history_long에서 FOR UPDATE -> COMMIT/ROLLBACK 페어링에 성공한 샘플이 없음(버퍼 유실 또는 이번 run에 bid 시도 자체가 없었을 가능성)'
      : (uncorrelated > 0
          ? `${uncorrelated}건은 같은 THREAD_ID에서 다음 COMMIT/ROLLBACK을 찾지 못해 hold time 계산에서 제외(추정하지 않음)`
          : '전량 correlate됨'),
  };
  fs.writeFileSync(outPath, JSON.stringify(out, null, 2));
  console.log(`[lockhold:${runTag}] samples=${n} uncorrelated=${uncorrelated} avgMs=${avg === null ? 'N/A' : avg.toFixed(3)}`);
}

// printStackTrace() 블록 탐지: 컬럼 0에서 시작하고 *Exception|*Error를 포함하는 줄 다음에
// 탭/공백으로 시작하는 "at ..." 줄이 이어지는 경우만 스택트레이스로 인정한다(logback ERROR
// 라인이 우연히 "Exception"이라는 단어를 메시지에 포함하는 오탐을 줄이기 위함).
function extractStackTraces(text) {
  const lines = text.split('\n');
  const blocks = [];
  for (let i = 0; i < lines.length; i++) {
    const header = lines[i];
    if (!/^[A-Za-z][\w.$]*(Exception|Error)\b/.test(header)) continue;
    const next = lines[i + 1] || '';
    if (!/^\s+at\s/.test(next) && !/^Caused by:/.test(next)) continue;

    const blockLines = [header];
    let j = i + 1;
    while (j < lines.length && blockLines.length < 40) {
      const l = lines[j];
      if (/^\s+at\s/.test(l) || /^Caused by:/.test(l) || /^\s*\.\.\.\s*\d+\s*more/.test(l)) {
        blockLines.push(l);
        j++;
      } else {
        break;
      }
    }
    const classMatch = header.match(/^([A-Za-z][\w.$]*(?:Exception|Error))/);
    blocks.push({
      exceptionClass: classMatch ? classMatch[1] : header,
      sample: blockLines.join('\n'),
    });
    i = j - 1;
  }
  return blocks;
}

// summarize.js의 metricValue()와 동일한 이유(k6 버전에 따라 --summary-export 구조가 다름)로
// 같은 폴백을 그대로 쓴다.
function metricValue(metrics, name, key, fallback) {
  const m = metrics[name];
  if (!m) return fallback;
  const source = m.values ? m.values : m;
  return source[key] === undefined ? fallback : source[key];
}

function readSystemFailureCount(summaryJsonPath) {
  try {
    const data = JSON.parse(fs.readFileSync(summaryJsonPath, 'utf8'));
    return metricValue(data.metrics || {}, 'bid_system_failure', 'count', 0);
  } catch (e) {
    return null;
  }
}

// hot-auction-bid.js의 logFailure()가 찍는 형식과 정확히 맞물려야 한다(같은 키 순서/이름).
// k6 콘솔은 이 메시지를 logfmt로 한 번 더 감싸면서 내부 큰따옴표를 이스케이프한다
// (msg="SYS_FAIL ... error=\"...\" ...") - 언이스케이프 후 파싱한다.
function parseSysFailLines(k6ConsoleText) {
  const out = [];
  const rawLines = k6ConsoleText.split('\n');
  for (const raw of rawLines) {
    const msgMatch = raw.match(/msg="(.*)" source=console\s*$/);
    if (!msgMatch) continue;
    const unescaped = msgMatch[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\');
    if (!unescaped.startsWith('SYS_FAIL')) continue;
    const m = unescaped.match(
      /^SYS_FAIL kind=(\S+) ts=(\S+) vu=(\S+) httpStatus=(\S+) error="(.*?)" errorCodeK6=(\S+) errorCodeApp=(\S+) url=(\S+) durationMs=(\S+) idem=(\S+)$/
    );
    if (!m) continue;
    out.push({
      kind: m[1],
      ts: m[2],
      tsMs: Date.parse(m[2]),
      vu: m[3],
      httpStatus: m[4],
      error: m[5],
      errorCodeK6: m[6],
      errorCodeApp: m[7],
      url: m[8],
      durationMs: m[9],
      idem: m[10],
    });
  }
  return out;
}

// backend-live.log(이번 run 슬라이스)를 "타임스탬프 -> 라인"으로 파싱한다. Spring Boot 기본
// 로그 포맷(2026-09-11T14:15:15.429+09:00 ...)만 인식 - 그 외 형식의 라인은 시간 매칭에서
// 제외한다(그 라인 자체를 버리지는 않음, 인접 컨텍스트로는 여전히 쓸 수 있다).
function parseBackendLogTimestamps(backendLogText) {
  const lines = backendLogText.split('\n');
  const out = [];
  const re = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2})/;
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(re);
    if (m) {
      const tsMs = Date.parse(m[1]);
      if (!Number.isNaN(tsMs)) out.push({ tsMs, index: i });
    }
  }
  return { lines, timestamped: out };
}

function nearestBackendLines(backendParsed, targetTsMs, windowMs, contextLines) {
  const { lines, timestamped } = backendParsed;
  const inWindow = timestamped.filter((t) => Math.abs(t.tsMs - targetTsMs) <= windowMs);
  if (inWindow.length === 0) return null;
  inWindow.sort((a, b) => Math.abs(a.tsMs - targetTsMs) - Math.abs(b.tsMs - targetTsMs));
  const nearest = inWindow[0];
  const start = Math.max(0, nearest.index - contextLines);
  const end = Math.min(lines.length, nearest.index + contextLines + 1);
  return { nearestDeltaMs: nearest.tsMs - targetTsMs, snippet: lines.slice(start, end).join('\n') };
}

function rootcause(logPath, k6ConsoleLogPath, outPath, runTag, summaryJsonPath) {
  const systemFailureCount = readSystemFailureCount(summaryJsonPath);
  let text = null;
  let logExists = fs.existsSync(logPath);
  if (logExists) {
    text = fs.readFileSync(logPath, 'utf8');
  }

  const lines = [];
  lines.push(`# rootcause - ${runTag}`);
  lines.push('');
  lines.push(`- bid_system_failure(k6 카운트) = ${systemFailureCount === null ? 'N/A(summary json 파싱 실패)' : systemFailureCount}`);
  lines.push(`- backend-live.log 슬라이스 존재 여부 = ${logExists ? 'YES' : 'NO'}`);

  // k6 콘솔 로그의 SYS_FAIL 라인 파싱 - status=0의 net-level 실제 원인(res.error/res.error_code)을
  // k6가 직접 분류한 값으로 확인한다. 추정하지 않는다.
  let sysFails = [];
  const k6LogExists = fs.existsSync(k6ConsoleLogPath);
  if (k6LogExists) {
    sysFails = parseSysFailLines(fs.readFileSync(k6ConsoleLogPath, 'utf8'));
  }
  lines.push(`- k6-console 로그에서 파싱된 SYS_FAIL 라인 수 = ${k6LogExists ? sysFails.length : 'N/A(k6 콘솔 로그 없음)'}`);
  lines.push('');

  if (sysFails.length > 0) {
    lines.push('## httpStatus / errorCodeK6(=k6 net-level 실패 사유 코드) 분포');
    const statusCounts = new Map();
    const errCounts = new Map();
    for (const f of sysFails) {
      statusCounts.set(f.httpStatus, (statusCounts.get(f.httpStatus) || 0) + 1);
      const key = `${f.errorCodeK6} (${f.error})`;
      errCounts.set(key, (errCounts.get(key) || 0) + 1);
    }
    lines.push('httpStatus 분포:');
    for (const [k, v] of [...statusCounts.entries()].sort((a, b) => b[1] - a[1])) {
      lines.push(`  - status=${k}: ${v}건`);
    }
    lines.push('res.error / res.error_code 분포 (k6이 net-level에서 직접 분류한 실패 사유):');
    for (const [k, v] of [...errCounts.entries()].sort((a, b) => b[1] - a[1])) {
      lines.push(`  - ${k}: ${v}건`);
    }
    lines.push('');
    const durations = sysFails.map((f) => Number(f.durationMs)).filter((d) => !Number.isNaN(d));
    if (durations.length > 0) {
      durations.sort((a, b) => a - b);
      lines.push(`SYS_FAIL 요청들의 durationMs 분포: min=${durations[0].toFixed(1)} med=${durations[Math.floor(durations.length / 2)].toFixed(1)} max=${durations[durations.length - 1].toFixed(1)}`);
      lines.push('(HikariCP connectionTimeout=30000ms 근처에 몰려 있으면 pool 고갈 대기 후 실패했다는 뜻이고, 0ms대에 몰려 있으면 즉시 거부/리셋된 것이라 pool 대기와는 다른 원인이다 - 실제 값은 아래를 보고 판단, 추정하지 않는다.)');
      lines.push('');
    }
  }

  if (!logExists) {
    lines.push('N/A - 이번 run 구간의 backend-live.log 슬라이스가 없음(IntelliJ console-to-file이 꺼져있거나 경로가 다를 수 있음). 원인을 추정하지 않음.');
    fs.writeFileSync(outPath, lines.join('\n'));
    console.log(`[rootcause:${runTag}] log missing - N/A`);
    return;
  }

  if (systemFailureCount !== null && Number(systemFailureCount) === 0) {
    lines.push('system failure 0건 - 원인 분석 대상 없음.');
    fs.writeFileSync(outPath, lines.join('\n'));
    console.log(`[rootcause:${runTag}] system_failure=0`);
    return;
  }

  // 3. backend-live.log와 k6 실패 timestamp 상관분석: 각 SYS_FAIL 시각(ts, UTC) 기준
  // ±1000ms 안에 backend-live.log 라인이 하나라도 있는지 찾는다 - "요청이 JVM에 도달한
  // 흔적조차 없다"와 "그 순간 서버는 다른 걸 하고 있었다"를 구분하기 위함이다(정확한
  // request 단위 매칭이 아니라 시간대 상관관계 - 명시한다).
  const backendParsed = parseBackendLogTimestamps(text);
  let withNearby = 0;
  let withoutNearby = 0;
  const samples = [];
  for (const f of sysFails) {
    if (Number.isNaN(f.tsMs)) continue;
    const near = nearestBackendLines(backendParsed, f.tsMs, 1000, 2);
    if (near) {
      withNearby++;
      if (samples.length < 3) samples.push({ f, near });
    } else {
      withoutNearby++;
    }
  }
  if (sysFails.length > 0) {
    lines.push('## backend-live.log 시간대 상관분석 (±1000ms 윈도우, request 단위 매칭 아님)');
    lines.push(`- SYS_FAIL 시각 ±1000ms 안에 backend-live.log 라인이 존재: ${withNearby}건`);
    lines.push(`- SYS_FAIL 시각 ±1000ms 안에 backend-live.log 라인이 전혀 없음: ${withoutNearby}건 (서버 프로세스가 이 요청의 흔적을 전혀 안 남겼다는 뜻 - JVM 도달 이전 실패 가능성)`);
    lines.push('');
    for (const { f, near } of samples) {
      lines.push(`### 샘플: SYS_FAIL ts=${f.ts} status=${f.httpStatus} error=${f.error} errorCodeK6=${f.errorCodeK6} durationMs=${f.durationMs} (가장 가까운 backend-live.log와 ${near.nearestDeltaMs}ms 차이)`);
      lines.push('```');
      lines.push(near.snippet);
      lines.push('```');
      lines.push('');
    }
  }

  const blocks = extractStackTraces(text);
  if (blocks.length === 0) {
    lines.push('backend-live.log 이번 run 구간에서 printStackTrace() 형태의 스택트레이스를 찾지 못함.');
    lines.push('주의: 40909(PessimisticLockingFailureException)는 GlobalExceptionHandler가 어떤 로그도 남기지 않으므로(소스 확인됨),');
    lines.push('system_failure 중 lock-related(40909) 비중이 크면 이 결과는 "원인 불명"이 아니라 "애초에 로그가 없다"는 뜻이다.');
    lines.push('실제 DB 레벨 lock wait timeout(1205)/deadlock(1213) 여부는 같은 run의 observability-*.json의 mysqlLockWaitTimeout1205Delta/mysqlDeadlock1213Delta를 봐라.');
    fs.writeFileSync(outPath, lines.join('\n'));
    console.log(`[rootcause:${runTag}] no stack traces found in log`);
    return;
  }

  const byClass = new Map();
  for (const b of blocks) {
    if (!byClass.has(b.exceptionClass)) {
      byClass.set(b.exceptionClass, { count: 0, sample: b.sample });
    }
    byClass.get(b.exceptionClass).count++;
  }
  const sorted = [...byClass.entries()].sort((a, b) => b[1].count - a[1].count);

  lines.push(`backend-live.log에서 발견된 예외 발생(스택트레이스 블록) 총 ${blocks.length}건, 서로 다른 클래스 ${sorted.length}종.`);
  lines.push('주의: 이 건수는 시간대상 같은 run 구간에 찍힌 것일 뿐, k6의 개별 실패 요청과 1:1로 매칭한 것이 아니다(request-id/traceId가 없어 정확한 상관관계 불가 - uncorrelated로 취급).');
  lines.push('');
  for (const [cls, info] of sorted) {
    lines.push(`## ${cls} (count=${info.count})`);
    lines.push('```');
    lines.push(info.sample);
    lines.push('```');
    lines.push('');
  }

  fs.writeFileSync(outPath, lines.join('\n'));
  console.log(`[rootcause:${runTag}] found ${blocks.length} stack trace blocks across ${sorted.length} classes`);
}

const [, , cmd, ...rest] = process.argv;
if (cmd === 'lockhold') {
  const [tsvPath, outPath, runTag] = rest;
  lockhold(tsvPath, outPath, runTag);
} else if (cmd === 'rootcause') {
  const [logPath, k6ConsoleLogPath, outPath, runTag, summaryJsonPath] = rest;
  rootcause(logPath, k6ConsoleLogPath, outPath, runTag, summaryJsonPath);
} else {
  console.error('사용법: node observability.js lockhold <raw.tsv> <out.json> <runTag>');
  console.error('       node observability.js rootcause <slicedLog> <k6ConsoleLog> <out.md> <runTag> <summaryJsonPath>');
  process.exit(1);
}
