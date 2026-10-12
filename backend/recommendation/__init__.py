# AI-generated with Claude Code (Claude Opus 5.5), 2026-10-08, reviewed by Dongkun Moon
"""Body-aware outfit recommendation from the user's own wardrobe (pure Python, no Django).

K-Fashion outfits teach which garments go together; the user's garments, labelled on their body,
are what gets recommended. Scores = hard filters x weighted sum of compatibility, body/fit, colour,
season and the user's taste. See docs/recommendation/README.md.
"""
