# 순수 추천 계산 시간 측정 (HTTP·DB·스레드 대기 제외) — 파드 안에서 실행
#   kubectl exec -i -n team14 deploy/backend -c backend -- env USER_NAME=lt_001 python manage.py shell < inproc_timing.py
# k6로 잰 전체 응답시간과 비교해서, 차이가 크면 "계산"이 아니라 "대기/DB"가 병목
import os, statistics, time
from apps.outfits import views
from apps.users.models import User
from apps.wardrobe.models import Garment

user = User.objects.get(username=os.environ.get('USER_NAME', 'lt_001'))
garments = {str(g.id): g for g in Garment.objects.filter(user=user, saved=True)}
data = {'body': user.body_profile, 'temp': 16, 'count': 3}
views.models()  # 모델 로딩은 측정에서 제외 (프로세스당 1회)

samples = []
for _ in range(int(os.environ.get('RUNS', '100'))):
    t = time.perf_counter()
    views._calculate(data, garments)
    samples.append((time.perf_counter() - t) * 1000)
samples.sort()
q = lambda p: samples[min(len(samples) - 1, int(len(samples) * p))]
print(f'옷 {len(garments)}벌 · {len(samples)}회  p50 {q(0.5):.1f}ms  p95 {q(0.95):.1f}ms  max {samples[-1]:.1f}ms  mean {statistics.mean(samples):.1f}ms')
