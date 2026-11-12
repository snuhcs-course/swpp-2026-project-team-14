# 부하 테스트 계정 삭제 (토큰·옷·테스트 중 생성된 추천 코디까지 CASCADE로 삭제)
#   kubectl exec -i -n team14 deploy/backend -c backend -- python manage.py shell < cleanup_users.py
from apps.users.models import User
deleted = User.objects.filter(username__regex=r'^lt_\d{3}$').delete()
print('삭제:', deleted)
