// 평소 트래픽 (2026-11-26, 운영 모드): 작은 은행 하나가 하루 종일 돌아가는 모습을 만든다.
// 목적은 한계 측정이 아니라 "늘 데이터가 흐르는 상태": 매일 대사가 실제 데이터로 돌고, 대시보드에 평소 모양이 생기고,
// 장애를 걸었을 때 평소와 무엇이 달라지는지 비교할 바탕이 된다.
//
// 실행: **맥북에서** 반복 실행기(run-local.sh)가 이 스크립트를 끝없이 돌린다 (2026-11-26 개발자 결정). 조종은 steadyctl.sh / Raycast "Bank Traffic"
//   클러스터 밖에 두는 이유: 부하를 주는 쪽이 검사받는 시스템 안에 있으면 노드 장애 때 같이 죽는다 (pitfalls 43).
//   그리고 진짜 고객과 같은 길로 들어온다: MetalLB IP → Traefik(Ingress) → gateway
//   한 번 실행 = 최대 10분 (실행기가 RUN_SECONDS로 정함, 평상시는 정각에서 끊는다). 매 실행마다 그 시각(KST)의 송금량으로 → 시간대별 패턴
//
// 단계 (LEVEL): 평상시(normal) = 아래 시간대별 표. 1~3단계 = 시간대 무시하고 고정 송금량, 정한 시간이 지나면 실행기가 평상시로 되돌린다
//   1단계 "바쁜 날"       30건/초,  60분   (점심 피크의 20배. 월급날·이벤트)
//   2단계 "꺾이기 직전"   150건/초, 30분   (5주 차 측정: p99가 200~400건/초 사이에서 꺾였다)
//   3단계 "붕괴"          800건/초, 10분   (5주 차 측정: 800건/초에서 transfer·gateway OOMKilled → 파드가 죽을 수 있다)
//   자동으로 되돌리는 이유: 켜 둔 채 잊으면 밤새 수백만 건이 쌓여 DB 디스크가 찬다. 실험은 끝나는 시각이 있어야 한다
// 계좌: load-test/steady/seed.sh가 만든 load-test/data/steady-accounts.json
//
// 섞는 것 (한 번 = 고객 행동 하나):
//   70% 당행 송금 · 22% 타행 송금(그중 3%는 없는 계좌 → 외부 거절 → 출금 보상) · 3% 잔액 부족(FAILED) · 5% 무통장입금(돈이 은행으로 들어오는 길)
// 전부 고객 입구(gateway Ingress)로 들어간다
// 결과 불명은 여기서 만들지 않는다: bank-mock의 "평소 외부 은행" 설정(가끔 응답 지연)이 만든다 (deploy/apps/bank-mock/values.yaml)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY_URL || 'http://gateway.172.30.1.240.nip.io';   // 고객 입구 (Ingress)
const SCALE = Number(__ENV.RATE_SCALE || '1');           // 평상시 송금량 배율 (0.5면 절반)
const LEVEL = __ENV.LEVEL || 'normal';
const LEVELS = {   // 초당 건수, VU(동시에 기다리는 요청) 준비 수·최대
  1: { perSecond: 30, vus: 20, maxVUs: 300 },
  2: { perSecond: 150, vus: 100, maxVUs: 1000 },
  3: { perSecond: 800, vus: 400, maxVUs: 3000 },   // 응답이 3초로 늘면 800 × 3 = 2,400개가 동시에 기다린다
};
const accounts = JSON.parse(open(__ENV.ACCOUNTS_FILE || '../data/steady-accounts.json'));

// KST 시각별 초당 건수. 새벽은 한산, 점심·퇴근 무렵이 가장 붐빈다. 평균 약 0.8건/초 ≈ 하루 7만 건
const RATE_BY_HOUR = [
  0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 0.3, 0.6,   // 00~07
  0.8, 1.0, 1.0, 1.0, 1.5, 1.0, 1.0, 1.0,   // 08~15
  1.0, 1.0, 1.5, 1.5, 0.8, 0.8, 0.6, 0.4,   // 16~23
];
const kstHour = (new Date().getUTCHours() + 9) % 24;
const level = LEVELS[LEVEL];
if (LEVEL !== 'normal' && !level) throw new Error(`알 수 없는 LEVEL: ${LEVEL} (normal | 1 | 2 | 3)`);
const perSecond = level ? level.perSecond : RATE_BY_HOUR[kstHour] * SCALE;
const perMinute = Math.max(1, Math.round(perSecond * 60));
const runSeconds = Number(__ENV.SMOKE_SECONDS || __ENV.RUN_SECONDS || 600);   // 토큰 수명(15분)보다 짧게

const outcome = new Counter('steady_outcome');   // 행동 종류 × 결과 (요약에 시간마다 찍힌다)

export const options = {
  scenarios: {
    steady: {
      // 정해진 속도로 보낸다 (응답을 기다렸다 보내면 느릴 때 덜 보내서 장애가 가려진다, coordinated omission)
      executor: 'constant-arrival-rate',
      rate: perMinute,
      timeUnit: '1m',
      duration: `${runSeconds}s`,   // SMOKE_SECONDS=20: 동작 확인용 짧은 실행
      preAllocatedVUs: level ? level.vus : 5,
      maxVUs: level ? level.maxVUs : 50,   // 외부 지연으로 응답이 늦어지면 동시에 기다리는 요청이 는다. 모자라면 dropped_iterations = 그 자체가 신호
    },
  },
  thresholds: { 'steady_outcome{kind:transfer}': ['count>=0'], 'steady_outcome{kind:interbank}': ['count>=0'] },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

const json = { 'Content-Type': 'application/json' };
const BANKS = ['004', '088', '020', '081', '011'];   // 국민·신한·우리·하나·농협 (기관 코드)

// 토큰은 실행마다 처음에 한 번 (수명 15분 > 한 번 실행 최대 10분). VU마다 받으면 3단계에서 토큰 발급이 부하가 된다
export function setup() {
  const tokens = {};
  for (const a of accounts) {
    const r = http.post(`${GATEWAY}/auth/token`, JSON.stringify({ userId: a.userId }), { headers: json, tags: { name: 'token' } });
    if (r.status === 200) tokens[a.userId] = r.json('access_token');
  }
  console.log(`단계 ${LEVEL}, 초당 ${perSecond}건, ${runSeconds}초, 토큰 ${Object.keys(tokens).length}/${accounts.length}`);
  return { tokens };
}

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
const between = (lo, hi, step) => lo + step * Math.floor(Math.random() * ((hi - lo) / step + 1));
const digits = (n) => Array.from({ length: n }, () => Math.floor(Math.random() * 10)).join('');

function sendTransfer(tokens, from, body, kind) {
  const token = tokens[from.userId];
  if (!token) { outcome.add(1, { kind, result: 'TOKEN_FAIL' }); return; }
  const res = http.post(`${GATEWAY}/transfers`, JSON.stringify({ fromAccountId: from.accountId, ...body }), {
    headers: { ...json, Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() },
    tags: { name: kind },
    timeout: '30s',
  });
  const result = res.status === 201 ? res.json('status') : `HTTP_${res.status}`;
  outcome.add(1, { kind, result });
  check(res, { [`${kind} 응답 201`]: (r) => r.status === 201 });
}

export default function (data) {
  const r = Math.random();
  const from = pick(accounts);

  if (r < 0.70) {
    let to = pick(accounts);
    while (to.accountId === from.accountId) to = pick(accounts);
    sendTransfer(data.tokens, from, { toAccountId: to.accountId, amount: between(1000, 100000, 100) }, 'transfer');
  } else if (r < 0.92) {
    // 3%는 없는 계좌(bank-mock 규칙: 999로 시작) → 외부 거절 → 보상(CANCELLED)
    const account = Math.random() < 0.03 ? `999${digits(9)}` : digits(12);
    sendTransfer(data.tokens, from, { toBankCode: pick(BANKS), toAccountNumber: account, amount: between(5000, 100000, 1000) }, 'interbank');
  } else if (r < 0.95) {
    let to = pick(accounts);
    while (to.accountId === from.accountId) to = pick(accounts);
    sendTransfer(data.tokens, from, { toAccountId: to.accountId, amount: 900000000 }, 'insufficient');   // 잔액 부족 → FAILED
  } else {
    // 입금: 무통장입금 흉내 (gateway POST /deposits, 2026-11-26 개방). 타행 송금으로 빠져나가는 돈을 채운다 (안 채우면 며칠 뒤 전부 잔액 부족)
    const res = http.post(`${GATEWAY}/deposits`, JSON.stringify({ accountId: from.accountId, amount: between(200000, 1000000, 10000) }), {
      headers: { ...json, Authorization: `Bearer ${data.tokens[from.userId]}`, 'Idempotency-Key': `steady-dep-${crypto.randomUUID()}` },
      tags: { name: 'deposit' },
    });
    outcome.add(1, { kind: 'deposit', result: `HTTP_${res.status}` });
  }
}
