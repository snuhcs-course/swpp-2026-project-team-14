# AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong), 2026-09-24, reviewed by Dongkun Moon and Hyeon U Jeong
from django.urls import include, path
from apps.usage.views import collect as collect_usage
from apps.usage.stability import collect as collect_stability

from apps.outfits.views import weather_view

from . import views

urlpatterns = [
    path("api/usage/events/", collect_usage),
    path("api/usage/stability/", collect_stability),
    path("api/", include("apps.users.urls")),
    path("healthz/", views.health),
    path("api/body-profile/", include("apps.body_profiles.urls")),
    path("api/wardrobe/", include("apps.wardrobe.urls")),
    path("api/fittings/", include("apps.fittings.urls")),
    path("api/outfits/", include("apps.outfits.urls")),
    path("api/weather/", weather_view),
]
