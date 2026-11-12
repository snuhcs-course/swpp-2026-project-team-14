// 무중단 배포 검증: 일정한 부하를 주는 동안 다른 터미널에서
//   sudo k3s kubectl rollout restart deployment/backend -n team14
// 를 실행하고, 실패 요청이 0건인지 확인한다
//   k6 run -e BASE=<BASE_URL> rollout.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const users = new SharedArray('users', () => JSON.parse(open('./tokens.json')));
const BASE = __ENV.BASE;

export const options = {
  scenarios: { steady: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 2), timeUnit: '1s',
                         duration: __ENV.DURATION || '8m', preAllocatedVUs: 20, maxVUs: 60 } },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'max'],
};

export default function () {
  const u = users[(__VU - 1) % users.length];
  const res = http.post(`${BASE}/api/outfits/recommend/`, JSON.stringify({ body: u.body, temp: 16, count: 3 }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${u.token}` }, timeout: '60s' });
  if (!check(res, { '200': (r) => r.status === 200 })) {
    console.log(`${new Date().toISOString()} 실패 status=${res.status} ${res.error || ''}`);  // 실패 시각 기록
  }
}
