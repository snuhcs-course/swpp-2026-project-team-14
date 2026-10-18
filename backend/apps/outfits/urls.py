# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
from django.urls import path

from . import views

urlpatterns = [
    path("", views.outfits_view),
    path("recommend/", views.recommend_view),
    path("<uuid:outfit_id>/", views.outfit_view),
    path("<uuid:outfit_id>/feedback/", views.feedback_view),
]
