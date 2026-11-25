// 평소 트래픽 (2026-11-26, 운영 모드): 작은 은행 하나가 하루 종일 돌아가는 모습을 만든다.
// 목적은 한계 측정이 아니라 "늘 데이터가 흐르는 상태": 매일 대사가 실제 데이터로 돌고, 대시보드에 평소 모양이 생기고,
// 장애를 걸었을 때 평소와 무엇이 달라지는지 비교할 바탕이 된다.
//
// 실행: **맥북에서** ./load-test/steady/run-local.sh 가 이 스크립트를 무한 반복한다 (2026-11-26 개발자 결정).
//   클러스터 밖에 두는 이유: 부하를 주는 쪽이 검사받는 시스템 안에 있으면 노드 장애 때 같이 죽는다 (pitfalls 43).
//   그리고 진짜 고객과 같은 길로 들어온다: MetalLB IP → Traefik(Ingress) → gateway
//   한 번 실행 = 다음 정각까지. 매 실행마다 그 시각(KST)의 송금량으로 → 시간대별 패턴이 저절로 생긴다
// 계좌: load-test/steady/seed.sh가 만든 load-test/data/steady-accounts.json
//
// 섞는 것 (한 번 = 고객 행동 하나):
//   70% 당행 송금 · 22% 타행 송금(그중 3%는 없는 계좌 → 외부 거절 → 출금 보상) · 3% 잔액 부족(FAILED) · 5% 입금(돈이 은행으로 들어오는 길)
// 결과 불명은 여기서 만들지 않는다: bank-mock의 "평소 외부 은행" 설정(가끔 응답 지연)이 만든다 (deploy/apps/bank-mock/values.yaml)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY_URL || 'http://gateway.172.30.1.240.nip.io';   // 고객 입구 (Ingress)
const CORE = __ENV.CORE_URL || 'http://localhost:18080';   // 입금은 고객 입구에 없다 (ADR-0002) → 관리자 통로 = core port-forward (run-local.sh가 열어 둔다)
const SCALE = Number(__ENV.RATE_SCALE || '1');           // 전체 송금량 배율 (0.5면 절반)
const accounts = JSON.parse(open(__ENV.ACCOUNTS_FILE || '../data/steady-accounts.json'));

// KST 시각별 초당 건수. 새벽은 한산, 점심·퇴근 무렵이 가장 붐빈다. 평균 약 0.8건/초 ≈ 하루 7만 건
const RATE_BY_HOUR = [
  0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 0.3, 0.6,   // 00~07
  0.8, 1.0, 1.0, 1.0, 1.5, 1.0, 1.0, 1.0,   // 08~15
  1.0, 1.0, 1.5, 1.5, 0.8, 0.8, 0.6, 0.4,   // 16~23
];
const now = new Date();
const kstHour = (now.getUTCHours() + 9) % 24;
const secondsToNextHour = 3600 - (now.getUTCMinutes() * 60 + now.getUTCSeconds());
const perMinute = Math.max(1, Math.round(RATE_BY_HOUR[kstHour] * 60 * SCALE));

const outcome = new Counter('steady_outcome');   // 행동 종류 × 결과 (요약에 시간마다 찍힌다)

export const options = {
  scenarios: {
    steady: {
      // 정해진 속도로 보낸다 (응답을 기다렸다 보내면 느릴 때 덜 보내서 장애가 가려진다, coordinated omission)
      executor: 'constant-arrival-rate',
      rate: perMinute,
      timeUnit: '1m',
      duration: __ENV.SMOKE_SECONDS ? `${__ENV.SMOKE_SECONDS}s` : `${Math.max(secondsToNextHour, 30)}s`,   // SMOKE_SECONDS=20: 동작 확인용 짧은 실행
      preAllocatedVUs: 5,
      maxVUs: 50,   // 외부 지연으로 응답이 늦어지면 동시에 기다리는 요청이 는다. 모자라면 dropped_iterations = 그 자체가 신호
    },
  },
  thresholds: { 'steady_outcome{kind:transfer}': ['count>=0'], 'steady_outcome{kind:interbank}': ['count>=0'] },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

const json = { 'Content-Type': 'application/json' };
const BANKS = ['004', '088', '020', '081', '011'];   // 국민·신한·우리·하나·농협 (기관 코드)

// 토큰은 VU마다 사용자별로 받아 두고 10분마다 새로 (수명 15분)
const tokens = {};
function tokenOf(userId) {
  const t = tokens[userId];
  if (t && Date.now() - t.at < 10 * 60 * 1000) return t.value;
  const r = http.post(`${GATEWAY}/auth/token`, JSON.stringify({ userId }), { headers: json, tags: { name: 'token' } });
  if (r.status !== 200) return null;
  tokens[userId] = { value: r.json('access_token'), at: Date.now() };
  return tokens[userId].value;
}

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
const between = (lo, hi, step) => lo + step * Math.floor(Math.random() * ((hi - lo) / step + 1));
const digits = (n) => Array.from({ length: n }, () => Math.floor(Math.random() * 10)).join('');

function sendTransfer(from, body, kind) {
  const token = tokenOf(from.userId);
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

export default function () {
  const r = Math.random();
  const from = pick(accounts);

  if (r < 0.70) {
    let to = pick(accounts);
    while (to.accountId === from.accountId) to = pick(accounts);
    sendTransfer(from, { toAccountId: to.accountId, amount: between(1000, 100000, 100) }, 'transfer');
  } else if (r < 0.92) {
    // 3%는 없는 계좌(bank-mock 규칙: 999로 시작) → 외부 거절 → 보상(CANCELLED)
    const account = Math.random() < 0.03 ? `999${digits(9)}` : digits(12);
    sendTransfer(from, { toBankCode: pick(BANKS), toAccountNumber: account, amount: between(5000, 100000, 1000) }, 'interbank');
  } else if (r < 0.95) {
    let to = pick(accounts);
    while (to.accountId === from.accountId) to = pick(accounts);
    sendTransfer(from, { toAccountId: to.accountId, amount: 900000000 }, 'insufficient');   // 잔액 부족 → FAILED
  } else {
    // 입금: 급여·타행 입금 흉내. 타행 송금으로 빠져나가는 돈을 채운다 (안 채우면 며칠 뒤 전부 잔액 부족)
    const res = http.post(`${CORE}/postings/deposits`, JSON.stringify({ accountId: from.accountId, amount: between(200000, 1000000, 10000) }), {
      headers: { ...json, 'Idempotency-Key': `steady-dep-${crypto.randomUUID()}` },
      tags: { name: 'deposit' },
    });
    outcome.add(1, { kind: 'deposit', result: `HTTP_${res.status}` });
  }
}
