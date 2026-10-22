# AI-generated with Codex, 2026-10-03, reviewed by Hyeon U Jeong; recommendation labels added with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
from django.urls import path

from . import views

urlpatterns = [
    path('analyze/', views.analyze),
    path('landmarks/', views.landmarks),
    path('options/', views.editor_options),
    path('labels/', views.labels),
    path('items/', views.items),
    path('items/<uuid:item_id>/', views.item),
    path('items/<uuid:item_id>/image/', views.item_image),
]
