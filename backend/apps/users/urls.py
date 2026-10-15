# AI-generated with Codex, 2026-10-16
from django.urls import path

from . import views


urlpatterns = [
    path('auth/register/', views.register, name='register'),
    path('auth/login/', views.login, name='login'),
    path('auth/logout/', views.logout, name='logout'),
    path('users/me/', views.me, name='me'),
]
