from django.urls import path

from . import views

urlpatterns = [
    path('analyze/', views.analyze),
    path('landmarks/', views.landmarks),
    path('options/', views.editor_options),
    path('items/', views.items),
    path('items/<uuid:item_id>/', views.item),
    path('items/<uuid:item_id>/image/', views.item_image),
]
