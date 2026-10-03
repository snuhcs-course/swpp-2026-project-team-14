from django.db import migrations


def remove_unused_attributes(apps, schema_editor):
    Garment = apps.get_model('wardrobe', 'Garment')
    records = Garment.objects.using(schema_editor.connection.alias)
    removed = {'formality', 'closure', 'details', 'shoulder_construction', 'neckline'}
    for garment in records.only('id', 'attributes', 'original_attributes').iterator():
        changes = {}
        for field in ('attributes', 'original_attributes'):
            attributes = getattr(garment, field)
            if isinstance(attributes, dict) and removed & attributes.keys():
                changes[field] = {key: value for key, value in attributes.items() if key not in removed}
        if changes:
            records.filter(pk=garment.pk).update(**changes)


class Migration(migrations.Migration):
    dependencies = [('wardrobe', '0002_remove_pattern_and_length')]
    operations = [
        migrations.RunPython(remove_unused_attributes, migrations.RunPython.noop),
        migrations.RemoveField(model_name='garment', name='user_properties'),
    ]
