from django.db import models


class ProbeItem(models.Model):
    name = models.CharField(max_length=50)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['-id']
