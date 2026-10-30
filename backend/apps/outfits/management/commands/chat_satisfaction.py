"""채팅 추천 만족도 집계: python manage.py chat_satisfaction

만족도 = 좋아요 / (좋아요 + 별로예요), 대상은 채팅에서 만족도를 남긴 코디(source=chat)다.
통과 기준(PASS_THRESHOLD)은 데이터를 모으기 전에 정한 값이며, 결과를 보고 바꾸지 않는다.
"""

from django.core.management.base import BaseCommand

from apps.outfits.models import Outfit

PASS_THRESHOLD = 0.5  # 만족도 50% 이상이면 통과


def satisfaction() -> dict:
    rated = Outfit.objects.filter(source=Outfit.Source.CHAT, feedback__isnull=False)
    liked = rated.filter(feedback=Outfit.Feedback.LIKED).count()
    disliked = rated.filter(feedback=Outfit.Feedback.DISLIKED).count()
    total = liked + disliked
    rate = liked / total if total else None
    return {"liked": liked, "disliked": disliked, "total": total, "rate": rate,
            "threshold": PASS_THRESHOLD, "passed": rate is not None and rate >= PASS_THRESHOLD}


class Command(BaseCommand):
    help = "채팅 추천 만족도(좋아요 비율)를 집계하고 통과 기준과 비교합니다."

    def handle(self, *args, **options):
        result = satisfaction()
        if result["total"] == 0:
            self.stdout.write("채팅 만족도 응답이 아직 없습니다.")
            return
        verdict = "통과" if result["passed"] else "미달"
        self.stdout.write(
            f"좋아요 {result['liked']} / 별로예요 {result['disliked']} (응답 {result['total']}건)\n"
            f"만족도 {result['rate']:.1%} (기준 {PASS_THRESHOLD:.0%} 이상) → {verdict}")
