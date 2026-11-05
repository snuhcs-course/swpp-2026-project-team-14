from django.urls import path
from . import views

urlpatterns = [
    path("health/", views.health),
    path("jobs/", views.submit),
    path("jobs/<uuid:job_id>/", views.job),
    path("jobs/<uuid:job_id>/image/", views.image),
]
