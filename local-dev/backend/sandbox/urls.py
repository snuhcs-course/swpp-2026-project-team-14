from django.urls import path
from probe import views

urlpatterns = [
    path('api/dev/health/', views.health),
    path('api/dev/items/', views.items),
]
