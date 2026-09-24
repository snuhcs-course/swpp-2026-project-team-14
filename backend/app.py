import os
import secrets

from django.conf import settings

settings.configure(
    DEBUG=False,
    # 세션·로그인 없이 응답만 하는 데모용.
    # 인증 기능을 추가할 때는 고정된 Secret을 별도로 주입해야 함.
    SECRET_KEY=os.environ.get("DJANGO_SECRET_KEY") or secrets.token_urlsafe(64),
    ALLOWED_HOSTS=["localhost", "127.0.0.1", "backend"],
    ROOT_URLCONF=__name__,
    INSTALLED_APPS=[],
    MIDDLEWARE=[],
)

from django.http import JsonResponse
from django.urls import path
from django.views.decorators.http import require_GET
from django.core.wsgi import get_wsgi_application


@require_GET
def hello(request):
    return JsonResponse({"message": "Hello, world!!!!!!!!"})


@require_GET
def health(request):
    return JsonResponse({"status": "ok"})


urlpatterns = [
    path("api/hello/", hello),
    path("healthz/", health),
]

application = get_wsgi_application()

