# AI-generated with Codex, 2026-10-16
from django.urls import path

from . import views, imports


urlpatterns = [
    path('auth/register/', views.register, name='register'),
    path('auth/login/', views.login, name='login'),
    path('auth/logout/', views.logout, name='logout'),
    path('users/me/', views.me, name='me'),
    path('users/me/import/preview/', imports.preview, name='import-preview'),
    path('users/me/import/profile/<str:section>/', imports.profile, name='import-profile'),
    path('users/me/import/garments/<uuid:source_id>/', imports.garment, name='import-garment'),
    path('users/me/import/outfits/<uuid:source_id>/', imports.outfit, name='import-outfit'),
]
