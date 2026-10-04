import re

from django.db import migrations


# Historical mapping stays fixed when the current catalog changes.
COLORS = dict(zip(
    'black white gray ivory beige brown navy blue green khaki red orange yellow pink purple silver gold'.split(),
    '#202020 #FFFFFF #808080 #FFFFF0 #D6BE9A #795548 #24344B #3975C6 #4F7952 #7B8052 #C83C3C #E88A3D #E8C547 #E8A0B0 #9165AD #C0C0C0 #C9A447'.split(),
))


def update_attributes(apps, schema_editor):
    Garment = apps.get_model('wardrobe', 'Garment')
    records = Garment.objects.using(schema_editor.connection.alias)
    for garment in records.only('id', 'attributes', 'original_attributes').iterator():
        changes = {}
        for field in ('attributes', 'original_attributes'):
            previous = getattr(garment, field)
            if not isinstance(previous, dict):
                continue
            attributes = dict(previous)
            attributes.pop('sleeve_length', None)
            attributes['styles'] = [style for style in attributes.get('styles', []) if style != 'lovely']
            colors = []
            for value in attributes.get('colors', []):
                color = COLORS.get(value)
                if color is None and isinstance(value, str) and re.fullmatch(r'#[0-9A-Fa-f]{6}', value):
                    color = value.upper()
                if color is not None and color not in colors:
                    colors.append(color)
            attributes['colors'] = colors
            if attributes != previous:
                changes[field] = attributes
        if changes:
            records.filter(pk=garment.pk).update(**changes)


class Migration(migrations.Migration):
    dependencies = [('wardrobe', '0003_simplify_garment_attributes')]
    operations = [migrations.RunPython(update_attributes, migrations.RunPython.noop)]
