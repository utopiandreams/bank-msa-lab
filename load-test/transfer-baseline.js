// k6 첫 기준선: 고객 경로 그대로 당행 송금 (k6 → Traefik → gateway → transfer → core)
//
//   ./load-test/seed-accounts.sh          # 먼저 계좌 준비 (load-test/data/accounts.json)
//   k6 run load-test/transfer-baseline.js
//   SMOKE=1 k6 run load-test/transfer-baseline.js   # 20초짜리 동작 확인
//
// 목적: 절대 처리량이 아니라 "평소 모양"과 "어디서 꺾이고 그때 무엇이 막히나" (docs/experiments/week-05-baseline.md)
//
// 설계:
//   - ramping-arrival-rate: 응답을 기다리지 않고 정해진 속도로 보낸다. 응답을 기다리고 다음 요청을 보내는 방식(VU 루프)은
//     서버가 느려지면 요청도 덜 보내서 느린 구간을 덜 재는 착시가 생긴다 (coordinated omission)
//   - 계좌 200개에서 무작위로 짝지음: 같은 계좌 경합이 없는 "평소 모습". 경합은 7주 차 실험의 주제
//   - 단계마다 stage 태그 → 요약에서 단계별 p99를 따로 본다
//   - teardown에서 전체 잔액 합이 처음과 같은지 확인 (당행 송금은 돈이 옮겨 갈 뿐이라 합이 변하면 버그)
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.GATEWAY_URL || 'http://gateway.172.30.1.240.nip.io';
const SMOKE = __ENV.SMOKE === '1';
const accounts = JSON.parse(open('./data/accounts.json'));

const statusCount = new Counter('transfer_status');   // 응답 본문의 송금 상태별 건수 (COMPLETED / FAILED / UNKNOWN)

// 단계: [이름, 초당 건수, 유지 시간(초)]. 단계 사이 10초는 속도를 올리는 구간
const STAGES = SMOKE
  ? [['smoke', 5, 20]]
  : [['warmup', 5, 60], ['10rps', 10, 120], ['30rps', 30, 120], ['60rps', 60, 120]];
const RAMP = 10;

function buildStages() {
  const out = [];
  for (const [, rate, hold] of STAGES) {
    out.push({ target: rate, duration: `${RAMP}s` });
    out.push({ target: rate, duration: `${hold}s` });
  }
  return out;
}

// 지금 몇 번째 단계인가 (경과 시간 기준). 속도를 올리는 10초는 앞 단계로 치지 않고 'ramp'로 따로 둔다
function currentStage() {
  let t = exec.instance.currentTestRunDuration / 1000;
  for (const [name, , hold] of STAGES) {
    if (t < RAMP) return 'ramp';
    t -= RAMP;
    if (t < hold) return name;
    t -= hold;
  }
  return 'ramp';
}

// 단계별 숫자를 요약에 따로 보이게 하려면 그 태그의 threshold가 있어야 한다 (k6 요약 규칙). 기준은 넉넉히 = 기록용
const thresholds = { http_req_failed: ['rate<0.01'] };
for (const [name] of STAGES) thresholds[`http_req_duration{stage:${name}}`] = ['p(99)<5000'];
for (const s of ['COMPLETED', 'FAILED', 'UNKNOWN']) thresholds[`transfer_status{status:${s}}`] = ['count>=0'];

export const options = {
  scenarios: {
    baseline: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 300,   // 응답이 느려지면 동시에 기다리는 요청이 늘어난다. 모자라면 k6가 "VU 부족" 경고 → 그 자체가 신호
      stages: buildStages(),
    },
  },
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const json = { 'Content-Type': 'application/json' };

// 토큰은 테스트 전에 한 번만 받는다 (수명 15분 → 전체 테스트가 15분을 넘으면 안 된다. 지금 약 8분)
export function setup() {
  if (accounts.length < 2) fail('accounts.json에 계좌가 2개 이상 필요 (seed-accounts.sh)');
  const tokens = {};
  for (const a of accounts) {
    const r = http.post(`${BASE}/auth/token`, JSON.stringify({ userId: a.userId }), { headers: json, tags: { name: 'token' } });
    if (r.status !== 200) fail(`토큰 발급 실패 ${a.userId}: ${r.status}`);
    tokens[a.userId] = r.json('access_token');
  }
  const total = sumBalances(tokens);
  console.log(`시작 전 총 잔액: ${total}원 (계좌 ${accounts.length}개)`);
  return { tokens, total };
}

export default function (data) {
  // 서로 다른 두 계좌를 무작위로
  const i = Math.floor(Math.random() * accounts.length);
  let j = Math.floor(Math.random() * (accounts.length - 1));
  if (j >= i) j += 1;
  const from = accounts[i];
  const to = accounts[j];

  const res = http.post(
    `${BASE}/transfers`,
    JSON.stringify({ fromAccountId: from.accountId, toAccountId: to.accountId, amount: 1 + Math.floor(Math.random() * 100) }),
    {
      headers: { ...json, Authorization: `Bearer ${data.tokens[from.userId]}`, 'Idempotency-Key': crypto.randomUUID() },
      tags: { name: 'transfer', stage: currentStage() },
    },
  );
  check(res, { 'transfer 201': (r) => r.status === 201 });
  const status = res.status === 201 ? res.json('status') : `HTTP_${res.status}`;
  statusCount.add(1, { status });
}

export function teardown(data) {
  const total = sumBalances(data.tokens);
  const ok = total === data.total;
  console.log(`끝난 뒤 총 잔액: ${total}원 → ${ok ? '✅ 처음과 같음' : `❌ 차이 ${total - data.total}원`}`);
  check(null, { '잔액 정합성 (전체 합 불변)': () => ok });
}

// 각 계좌를 주인의 토큰으로 조회해 더한다 (core는 주인만 조회 허용, G-4)
function sumBalances(tokens) {
  let sum = 0;
  for (const a of accounts) {
    const r = http.get(`${BASE}/accounts/${a.accountId}`, { headers: { Authorization: `Bearer ${tokens[a.userId]}` }, tags: { name: 'balance' } });
    if (r.status !== 200) fail(`잔액 조회 실패 ${a.accountId}: ${r.status}`);
    sum += r.json('balance');
  }
  return sum;
}
