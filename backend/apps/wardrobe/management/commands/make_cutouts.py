# AI-generated with Claude Code (Claude Opus 5.5), 2026-11-26, reviewed by Dongkun Moon
"""Make the stored rail cutout for saved garments that do not have a current one yet.

Run once after deploying stored cutouts; it is safe to run again (garments with a current cutout are skipped
without running the model). Garments missed here still get theirs on their first view in 옷장.
"""
from django.core.management.base import BaseCommand

from apps.wardrobe.analysis import AnalysisError
from apps.wardrobe.cutouts import HANGABLE, ensure_cutout
from apps.wardrobe.models import Garment


class Command(BaseCommand):
    help = "Make missing or stale wardrobe-rail cutouts for saved tops, bottoms and outerwear."

    def add_arguments(self, parser):
        parser.add_argument('--user', help='Only this username.')
        parser.add_argument('--limit', type=int, help='Process at most this many garments.')
        parser.add_argument('--dry-run', action='store_true', help='Only count the garments that would be processed.')

    def handle(self, *args, user=None, limit=None, dry_run=False, **options):
        garments = Garment.objects.filter(saved=True, attributes__category__in=HANGABLE).order_by('created_at')
        if user:
            garments = garments.filter(user__username=user)
        if limit:
            garments = garments[:limit]
        counts = {'made or current': 0, 'unclear': 0, 'missing photo': 0, 'failed': 0}
        garments = list(garments)
        if dry_run:
            self.stdout.write(f'{len(garments)} saved garments would be checked; nothing was written.')
            return
        for garment in garments:
            try:
                ensure_cutout(garment)
                counts['made or current'] += 1
            except AnalysisError as error:
                counts['unclear' if error.status == 422 else 'failed'] += 1
                self.stderr.write(f'{garment.pk}: {error.code}')
            except FileNotFoundError:
                counts['missing photo'] += 1
            except Exception as error:  # keep going; one bad photo must not stop the backfill
                counts['failed'] += 1
                self.stderr.write(f'{garment.pk}: {type(error).__name__}')
        self.stdout.write(', '.join(f'{name} {count}' for name, count in counts.items()))
