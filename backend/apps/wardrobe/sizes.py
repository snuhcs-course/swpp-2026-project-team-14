# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
"""How long a garment is in real life, for drawing outfits to scale (홈 and 코디북 styled looks).

A garment photo alone gives proportions but not size, so a coat, a tee and trousers would otherwise come
out at whatever size their photos happen to be. The length is taken, in order of trust, from a measured
total length (AR or typed by the user), the garment's length label, or a typical length for its kind.
Rough body-photo estimates are not used: they are often far off (a tee estimated at 48 cm).
"""

# Typical total lengths in cm (back neck or waistband to hem) for an adult, by kind.
TYPICAL_LENGTH_CM = {
    'top': {'tshirt': 68, 'shirt': 74, 'blouse': 64, 'sweatshirt': 66, 'hoodie': 68, 'knitwear': 64, 'other': 66},
    'outerwear': {'cardigan': 62, 'hooded_jacket': 66, 'fleece': 64, 'jacket': 62, 'coat': 100, 'puffer': 64,
                  'vest': 62, 'other': 66},
    'bottom': {'jeans': 100, 'slacks': 100, 'pants': 98, 'active_pants': 96, 'shorts': 46, 'skirt': 76, 'other': 96},
}
# Length labels (recommendation/labeling.py vocabulary) as typical lengths in cm.
LABEL_LENGTH_CM = {
    'top': {'크롭': 50, '노멀': 67, '롱': 78},
    'outerwear': {'크롭': 50, '노말': 64, '하프': 82, '롱': 105, '맥시': 120},
    'bottom': {'미니': 42, '니렝스': 58, '미디': 76, '발목': 98, '맥시': 106},
}
DEFAULT_LENGTH_CM = {'top': 67, 'outerwear': 66, 'bottom': 98}


def length_cm(garment) -> int | None:
    """Real total length of a top, bottom or outerwear in cm; None for other categories."""
    category = garment.attributes.get('category')
    if category not in TYPICAL_LENGTH_CM:
        return None
    measured = (garment.dimensions or {}).get('total_length')
    if isinstance(measured, dict) and isinstance(measured.get('value'), (int, float)) and 20 <= measured['value'] <= 160:
        return round(measured['value'])
    label = (garment.labels or {}).get('length')
    if label in LABEL_LENGTH_CM[category]:
        return LABEL_LENGTH_CM[category][label]
    return TYPICAL_LENGTH_CM[category].get(garment.attributes.get('subcategory') or 'other', DEFAULT_LENGTH_CM[category])
