# AI-generated with Claude Code (Claude Opus 5.5), 2026-09-24, reviewed by Dongkun Moon
from django.urls import path

from . import views

urlpatterns = [
    path("analyze/", views.analyze, name="body-profile-analyze"),
]
