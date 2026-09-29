from django.urls import path
from probe import views
from wardrobe import views as wardrobe

urlpatterns = [
    path('api/wardrobe/analyze/', wardrobe.analyze),
    path('api/wardrobe/options/', wardrobe.editor_options),
    path('api/wardrobe/items/', wardrobe.items),
    path('api/wardrobe/items/<uuid:item_id>/', wardrobe.item),
    path('api/wardrobe/items/<uuid:item_id>/image/', wardrobe.item_image),
    path('api/dev/health/', views.health),
    path('api/dev/items/', views.items),
]
