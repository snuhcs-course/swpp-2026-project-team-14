# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
from django.urls import path

from . import views
from .chat import chat_view

urlpatterns = [
    path("preview/", views.preview_view),
    path("preview/feedback/", views.preview_feedback),
    path("", views.outfits_view),
    path("recommend/", views.recommend_view),
    path("chat/", chat_view),
    path("<uuid:outfit_id>/", views.outfit_view),
    path("<uuid:outfit_id>/feedback/", views.feedback_view),
]
