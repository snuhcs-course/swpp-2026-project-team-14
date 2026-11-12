# Load test: outfit recommendation API

Load test for the home outfit recommendation button (`POST /api/outfits/recommend/`) on the deployed backend.
It measures three things without changing the service code:

| # | Measurement | Script |
|---|---|---|
| 1 | p50/p95 latency and failure rate while the request rate rises step by step | `recommend.js` (k6) |
| 2 | Recommendation time inside the backend pod (no HTTP, no queueing) | `inproc_timing.py` |
| 3 | Failed requests during a rolling restart of the backend | `rollout.js` (k6) |

## Before you start
- The target is the shared course server: tell the team and run it when nobody is using the app.
- Install [k6](https://k6.io/) on your PC.
- You need `kubectl` access to the `team14` namespace (`sudo k3s kubectl` on the server).
- Pick an existing account that has saved tops and bottoms and a body profile (`TEMPLATE`).

## Steps
Copy `seed_users.py`, `inproc_timing.py` and `cleanup_users.py` to the server, then:

**0. Create 20 test accounts** (on the server). The recommendation API locks the user row, so each virtual user needs its own account.
```bash
sudo k3s kubectl exec -i -n team14 deploy/backend -c backend -- \
  env TEMPLATE=<username> N=20 python manage.py shell < seed_users.py > tokens.json
```
Copy `tokens.json` next to the `.js` files on your PC. It contains tokens: never commit or share it.

**1. Step load** (on your PC, about 11 minutes). 1 → 8 RPS, each step 20 s ramp + 60 s hold.
```bash
k6 run -e BASE=<BASE_URL> -e STEP=1 -e STEPS=8 --out csv=result.csv recommend.js
```
Record `http_req_duration` p50/p95 and `http_req_failed`. Watch CPU with `sudo k3s kubectl top pod -n team14`.

**2. In-pod timing** (on the server, no load).
```bash
sudo k3s kubectl exec -i -n team14 deploy/backend -c backend -- \
  env USER_NAME=lt_001 python manage.py shell < inproc_timing.py
```

**3. Rolling restart** (about 8 minutes). Start k6, then restart the backend about one minute later.
```bash
k6 run -e BASE=<BASE_URL> -e RATE=2 rollout.js                        # PC
sudo k3s kubectl rollout restart deployment/backend -n team14         # server
sudo k3s kubectl rollout status deployment/backend -n team14
```
`http_req_failed` should be 0 %. Failed requests are printed with their time.

**4. Clean up** (always).
```bash
sudo k3s kubectl exec -i -n team14 deploy/backend -c backend -- python manage.py shell < cleanup_users.py
```
This deletes the `lt_NNN` accounts and the outfits created during the test. Delete `tokens.json` on the server and on your PC.

Results are recorded in the wiki Testing Documentation.
