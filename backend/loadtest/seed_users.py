# 부하 테스트용 계정 생성 (서버 파드 안에서 실행)
#   kubectl exec -i -n team14 deploy/backend -c backend -- env TEMPLATE=<옷장 있는 기존 아이디> N=20 python manage.py shell -v 0 < seed_users.py > tokens.json
#   (-v 0: Django 5.2가 자동 import 안내를 stdout에 찍어 JSON이 깨지지 않게 함)
# - TEMPLATE 사용자의 저장된 옷(saved=True)과 체형을 lt_001 ~ lt_0NN 계정에 복사한다
# - 각 계정에 토큰을 발급하고, k6가 읽을 JSON 배열을 stdout으로 출력한다
# - 사진 파일은 복사하지 않고 같은 경로를 참조만 한다 (계정 삭제 시 파일은 지워지지 않음)
import json, os, secrets, uuid
from django.db import transaction
from apps.users.models import User
from apps.users.auth import issue_token
from apps.wardrobe.models import Garment

template = User.objects.get(username=os.environ['TEMPLATE'])
n = int(os.environ.get('N', '20'))
garments = list(Garment.objects.filter(user=template, saved=True))
assert template.body_profile, 'TEMPLATE 사용자에 체형 정보가 없습니다'
assert garments, 'TEMPLATE 사용자에 저장된 옷이 없습니다'

out = []
for i in range(1, n + 1):
    name = f'lt_{i:03d}'
    with transaction.atomic():
        User.objects.filter(username=name).delete()  # 재실행 시 깨끗하게
        user = User.objects.create_user(name, secrets.token_urlsafe(24),
                                        body_profile=template.body_profile,
                                        preferred_fits=template.preferred_fits,
                                        preferred_styles=template.preferred_styles)
        for g in garments:
            Garment.objects.create(id=uuid.uuid4(), user=user, image=g.image.name, attributes=g.attributes,
                                   dimensions=g.dimensions, labels=g.labels, notes='', saved=True)
        _, raw = issue_token(user)
    out.append({'user': name, 'token': raw, 'body': template.body_profile})

print(json.dumps(out, ensure_ascii=False))
