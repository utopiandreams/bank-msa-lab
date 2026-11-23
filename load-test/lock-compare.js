// 7주 차: 락 전략 비교 (core 직접, 클러스터 안 k6 파드)
//
//   ./load-test/lock-compare.sh <hot|spread> <전략>      # 이 스크립트를 클러스터 안에서 돌리는 실행기 (전략 전환·정합성 확인 포함)
//
// 실험이 답하는 질문 (docs/experiments/week-07-locks.md):
//   hot    = 경합이 최악일 때 전략별 정합성·처리량·지연은?  계좌 1개 → 수신 계좌 200개로 1원씩 송금
//   spread = 경합이 없을 때 락의 비용은?                   계좌 200개끼리 무작위 송금 (5주 차 기준선과 같은 모양)
//
// 설계:
//   - core /postings/transfers 직접: gateway·transfer 구간의 지연이 섞이면 락 차이가 흐려진다
//   - constant-arrival-rate: 응답을 기다리지 않고 정해진 속도로 보낸다 (coordinated omission 회피, 5주 차와 같은 이유)
//   - 회차마다 setup()이 새 계좌를 만든다 (사용자 lock-<RUN>-NNN). NONE이 잔액을 깨뜨려도 다음 회차에 섞이지 않게
//   - 잔액은 넉넉히: 실패 원인이 "락 경합" 하나만 남게 (잔액 부족이 섞이면 해석이 두 갈래가 된다)
//   - 정합성은 k6가 아니라 실행기가 DB에서 본다 (잔액 vs 원장 합, 전체 합). k6는 응답 코드·지연만
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';

const CORE = __ENV.CORE_URL || 'http://core.bank.svc.cluster.local';
const SCENARIO = __ENV.SCENARIO || 'hot';
const RUN = __ENV.RUN || `${Date.now()}`;
const RATE = Number(__ENV.RATE || (SCENARIO === 'hot' ? 50 : 200));   // 초당 건수
const DURATION = __ENV.DURATION || '60s';
const RECEIVERS = 200;

// 시나리오별 계좌 준비: [잔액, 개수]
const HOT_BALANCE = 1_000_000_000;   // 1원 × 50건/초 × 60초 = 3,000원. 절대 바닥나지 않는다
const SPREAD_BALANCE = 1_000_000;    // 1~100원 × 200건/초 × 60초를 200개에 나누면 계좌당 수천 원

// 응답 코드별 건수를 요약에 따로 보이게 하려면 그 태그의 threshold가 있어야 한다 (k6 요약 규칙). 기준은 기록용
// 201 성공 / 409 낙관적 락 재시도 소진 등 충돌 / 422 업무 거절 / 500 매핑 안 된 예외 / 503 락 대기 포기
// setup()의 계좌 준비 요청이 섞이지 않게 phase:load만 센다
const thresholds = { 'http_req_duration{phase:load}': ['p(99)<60000'] };
for (const s of [201, 409, 422, 500, 503]) thresholds[`http_reqs{phase:load,status:${s}}`] = ['count>=0'];

export const options = {
  scenarios: {
    lock: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      // 동시에 기다리는 요청 = 초당 건수 × 응답 시간. 락 대기 1초(REDIS)면 50건/초에 50개.
      // 모자라면 dropped_iterations → 그 자체가 "밀렸다"는 신호
      maxVUs: 1000,   // 500건/초 × REDIS 대기 상한 1초 = 500개까지 필요할 수 있다
    },
  },
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
  setupTimeout: '120s',
};

function openAccount(user, balance) {
  const res = http.post(`${CORE}/accounts`, null, { headers: { 'X-User-Id': user }, tags: { phase: 'setup' } });
  if (res.status !== 200 && res.status !== 201) fail(`계좌 개설 실패 ${res.status} ${res.body}`);
  const accountId = res.json('accountId');
  if (balance === 0) return { userId: user, accountId };   // 입금은 0원을 받지 않는다 (금액 > 0)
  const dep = http.post(`${CORE}/postings/deposits`, JSON.stringify({ accountId, amount: balance }), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `seed-${accountId}` },
    tags: { phase: 'setup' },
  });
  if (dep.status !== 201 && dep.status !== 200) fail(`입금 실패 ${dep.status} ${dep.body}`);
  return { userId: user, accountId };
}

export function setup() {
  const n = (i) => `lock-${RUN}-${String(i).padStart(3, '0')}`;
  const accounts = [];
  if (SCENARIO === 'hot') {
    accounts.push(openAccount(n(0), HOT_BALANCE));                       // [0] = 핫 계좌
    for (let i = 1; i <= RECEIVERS; i++) accounts.push(openAccount(n(i), 0));
  } else if (SCENARIO === 'spread') {
    for (let i = 1; i <= RECEIVERS; i++) accounts.push(openAccount(n(i), SPREAD_BALANCE));
  } else {
    fail(`알 수 없는 SCENARIO: ${SCENARIO} (hot | spread)`);
  }
  console.log(`setup 완료: RUN=${RUN} SCENARIO=${SCENARIO} 계좌 ${accounts.length}개`);
  return { accounts };
}

function pick(accounts) {
  if (SCENARIO === 'hot') {
    const to = accounts[1 + Math.floor(Math.random() * RECEIVERS)];
    return { from: accounts[0], to, amount: 1 };
  }
  const i = Math.floor(Math.random() * accounts.length);
  let j = Math.floor(Math.random() * (accounts.length - 1));
  if (j >= i) j++;   // 자기 자신에게 보내지 않게
  return { from: accounts[i], to: accounts[j], amount: 1 + Math.floor(Math.random() * 100) };
}

export default function (data) {
  const { from, to, amount } = pick(data.accounts);
  const res = http.post(
    `${CORE}/postings/transfers`,
    JSON.stringify({ fromAccountId: from.accountId, toAccountId: to.accountId, amount }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': from.userId,   // 출금 계좌 소유자 (BOLA 검사, G-4)
        'Idempotency-Key': `lk-${RUN}-${exec.scenario.iterationInTest}`,
      },
      tags: { phase: 'load' },
    },
  );
  check(res, { '201': (r) => r.status === 201 });
}
