# AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong), 2026-09-24, reviewed by Dongkun Moon and Hyeon U Jeong
from django.urls import include, path

from . import views

urlpatterns = [
    path("api/hello/", views.hello),
    path("healthz/", views.health),
    path("api/body-profile/", include("apps.body_profiles.urls")),
    path("api/wardrobe/", include("apps.wardrobe.urls")),
]
