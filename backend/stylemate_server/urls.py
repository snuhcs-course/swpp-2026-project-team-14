from django.urls import include, path

urlpatterns = [
    path("api/body-profile/", include("body_profiles.urls")),
]
