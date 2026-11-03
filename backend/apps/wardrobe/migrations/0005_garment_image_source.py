from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [('wardrobe', '0004_alter_garment_user')]
    operations = [migrations.AddField(
        model_name='garment', name='image_source',
        field=models.CharField(max_length=24, default='garment_photo', choices=[
            ('garment_photo', '옷 사진'), ('worn_crop', '착용 사진 일부')]),
    )]
