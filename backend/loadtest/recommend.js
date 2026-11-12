// 추천 API 부하 테스트: 초당 요청 수를 단계적으로 올려 p95가 무너지는 지점을 찾는다
//   k6 run -e BASE=<BASE_URL> recommend.js
// - 테스트 계정마다 별도 토큰 사용: recommend_view가 사용자 행을 SELECT ... FOR UPDATE로 잠그기 때문에
//   한 계정으로만 보내면 서빙 성능이 아니라 "같은 사용자 락 대기"를 재게 된다
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const users = new SharedArray('users', () => JSON.parse(open('./tokens.json')));
const BASE = __ENV.BASE;
const STEP = Number(__ENV.STEP || 1);    // 단계당 증가 RPS
const STEPS = Number(__ENV.STEPS || 8);  // 단계 수 → 최대 STEP*STEPS RPS

const stages = [];
for (let i = 1; i <= STEPS; i++) { stages.push({ target: STEP * i, duration: '20s' }, { target: STEP * i, duration: '60s' }); }

export const options = {
  scenarios: {
    ramp: { executor: 'ramping-arrival-rate', startRate: STEP, timeUnit: '1s', stages,
            preAllocatedVUs: 50, maxVUs: 200 },
  },
  thresholds: { http_req_failed: ['rate<0.01'] },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const u = users[(__VU - 1) % users.length];
  const res = http.post(`${BASE}/api/outfits/recommend/`,
    JSON.stringify({ body: u.body, temp: 16, count: 3 }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${u.token}` }, timeout: '60s',
      });
  check(res, { '200': (r) => r.status === 200 });
}
