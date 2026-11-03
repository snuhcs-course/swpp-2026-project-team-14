# AI-generated with Codex, 2026-10-03, reviewed by Hyeon U Jeong; recommendation labels added with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
from django.urls import path

from . import views
from . import worn_views
from . import worn_photo_views

urlpatterns = [
    path('analyze/', views.analyze),
    path('analyze-worn/', views.analyze_worn),
    path('worn-photos/', worn_photo_views.upload),
    path('worn-photos/<uuid:photo_id>/', worn_photo_views.detail),
    path('worn-photos/<uuid:photo_id>/image/', worn_photo_views.image),
    path('worn-photos/<uuid:photo_id>/candidates/<uuid:candidate_id>/image/', worn_photo_views.image),
    path('worn-photos/<uuid:photo_id>/prepare/', worn_photo_views.prepare),
    path('worn-photos/<uuid:photo_id>/save/', worn_photo_views.save),
    path('landmarks/', views.landmarks),
    path('options/', views.editor_options),
    path('labels/', views.labels),
    path('items/', views.items),
    path('items/<uuid:item_id>/', views.item),
    path('items/<uuid:item_id>/image/', views.item_image),
    path('items/<uuid:item_id>/generation/', worn_views.generation),
    path('items/<uuid:item_id>/review/', worn_views.review),
    path('items/<uuid:item_id>/worn-image/<str:kind>/', worn_views.image),
]
