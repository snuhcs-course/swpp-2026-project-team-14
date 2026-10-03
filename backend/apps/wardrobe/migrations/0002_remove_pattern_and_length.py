from django.db import migrations


def remove_pattern_and_length(apps, schema_editor):
    Garment = apps.get_model('wardrobe', 'Garment')
    records = Garment.objects.using(schema_editor.connection.alias)
    for garment in records.only('id', 'attributes', 'original_attributes').iterator():
        changes = {}
        for field in ('attributes', 'original_attributes'):
            attributes = getattr(garment, field)
            if isinstance(attributes, dict) and {'pattern', 'length'} & attributes.keys():
                changes[field] = {key: value for key, value in attributes.items()
                                  if key not in ('pattern', 'length')}
        if changes:
            records.filter(pk=garment.pk).update(**changes)


class Migration(migrations.Migration):
    dependencies = [('wardrobe', '0001_initial')]
    operations = [migrations.RunPython(remove_pattern_and_length)]
