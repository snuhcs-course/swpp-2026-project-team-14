# Alpha-test usage collection

Issue: [#99](https://github.com/snuhcs-course/swpp-2026-project-team-14/issues/99). Branch: `feature/alpha-test`, based on `develop`.

The alpha test is on November 30, 2026. Store registration is due November 29 at 23:59 KST.

## Metrics

| Metric | Recorded boundary | Interpretation |
| --- | --- | --- |
| Duplicate recommendation | A successful `/api/outfits/recommend/` response or an `updated` chat result | `outfit_recommendation`: `total_count` is the number of returned candidates, `duplicate_count` counts combinations in the request's `seen_keys` or earlier in the same response. Sorted garment IDs define a combination; names and item order do not. Sources distinguish `home` and `chat`. This measures returned recommendations, including carousel candidates, rather than individual card impressions. |
| Duplicate save | A successful save from home, chat, or the outfit API | `outfit_save`: `already_saved` identifies a combination already in the user's saved outfits before this operation. `duplicate_count` is the number of saved rows beyond the first after saving. An idempotent chat save retry produces no additional event; unsaving and renaming produce none. |
| Garment registration time | Starting camera registration or entering worn-photo registration → confirmed server save | `garment_registration`. Sources distinguish `wardrobe`, `onboarding`, and `worn`. Worn-photo registration measures the whole batch and should be reported separately from single-garment registration. Editing an existing saved garment is excluded. |
| Virtual fitting time | Selecting generation → first draw of the resulting image | `virtual_fitting`. `generated` records the earlier point at which generation, download, and local result storage complete. `finished/success` records display. Closing the generation dialog allows the job to continue and is not a cancellation. Time until display includes time the user spends away from the app. |
| Chat outcome | Each sent request, including a manual retry → response, error, or cancellation | `chat_request`. An updated garment combination is success; `clarify`, `unlock`, and `undo` are valid responses. `unchanged`, a missing updated outfit, or an unchanged ID combination under `updated` is `OUTFIT_EDIT_FAILED`. Server codes, `NETWORK`, and `TIMEOUT` identify technical failures. Taking back an in-flight request is cancellation. |

Client attempts emit `started`, then one `finished` phase with `success`, `failure`, or `cancelled`. A manual retry after failure starts a new attempt at the retried action. Fitting also emits `generated`. Phase identities remain stable across fitting worker retries and process recreation. Registration checkpoints are restored with the screen/onboarding draft. Server outfit metrics use one terminal event per successful API operation; their session IDs identify the individual request rather than an Android app session.

Use only successful, non-null `duration_ms` values for completion-time median and p90. Report failure and cancellation counts separately. A `started` attempt without a terminal event is incomplete, not a success or an inferred failure. A fitting with `generated` but no finish has not been viewed. A chat edit failure describes an unsuccessful requested edit, not necessarily a server defect; tester feedback is still needed to assess interpretation quality.

- Recommendation duplicate rate: sum(`duplicate_count`) / sum(`total_count`) for `outfit_recommendation`.
- Save overlap rate: count(`outfit_save` with `already_saved=true`) / count(`outfit_save`). Report the post-save extra-row count separately.
- Task completion rate: successful finished attempts / started attempts, joined by account and attempt ID. Show incomplete attempts alongside failure and cancellation counts.
- Chat technical failure rate: failed requests excluding `OUTFIT_EDIT_FAILED` / finished requests excluding cancellations. Report edit failures and clarification responses separately.

## Storage and delivery

`POST /api/usage/events/` uses the existing Bearer authentication. The account comes from the token; clients cannot choose an event's owner. Each batch contains at most 25 strictly validated events and is limited to 32 KiB. There is no HTTP read/export endpoint.

The existing MySQL database stores `usage_usageevent`. `(user, attempt_id, phase)` is unique, and event IDs are immutable. Lost responses and repeated display callbacks therefore cannot add another measurement for the same phase. A batch is validated before any event is saved. Both occurrence and server receipt timestamps are retained.

Android stores pending events per account under its private, non-backed-up `usage/` directory, replacing the queue atomically. WorkManager uploads with network constraints and exponential backoff, and the client removes only acknowledged events. Pending events for another account wait until that account logs in again. App resume also schedules pending uploads. The queue keeps the most recent 2,000 events per account if a device remains offline for a long period; uninstalling or clearing app data removes unsent events.

Durations use a monotonic device clock. Fitting checkpoints retain the start time and boot counter. If the clock cannot be continued after a reboot, `clock_reset=true` and a null duration preserve the outcome without inventing a time. Photos, garment names/IDs, chat contents, body measurements, credentials, and exception messages are excluded. Stored account UUIDs permit per-tester grouping; the export does not contain login names.

## Deploy and export

Deploy the backend and apply migrations before distributing the instrumented app:

```sh
python manage.py migrate
python manage.py export_usage --since 2026-11-30T00:00:00+09:00 --until 2026-12-01T00:00:00+09:00 > alpha-2026-11-30.csv
```

Run the export inside the backend environment with its normal DB credentials. It writes CSV to stdout and uses an inclusive `--since` and exclusive `--until`. Re-export after offline testers reconnect so delayed events are included. Deploying this branch or changing the production database is a separate operational step; local tests use isolated SQLite.

## Alpha-test rehearsal

1. Use a dedicated tester account; register an item and cancel another registration. Force a registration failure and retry. Verify the phase, outcome, source, and elapsed time in the export.
2. Request recommendations repeatedly in a small wardrobe, then save equivalent combinations from home and chat. Verify recommendation counts, existing-save overlap, and post-save duplicates separately. Different garments with identical names must not count as a match.
3. Request one fitting using a synthetic or approved test image. Close the dialog and reopen the completed result. Verify `started → generated → finished/success` uses one attempt ID. Reopening the same result must not increase the number of terminal records.
4. In chat, exercise an actual edit, a clarification, no matching edit, a server error, a timeout, and taking back an in-flight request. Verify structured codes and that no message contents enter telemetry.
5. Disconnect the network, perform actions, reconnect, and reopen the app. Verify queued events arrive once. Switch accounts while events are pending and confirm ownership is unchanged.
6. Record observed defects, tester feedback, metric results, owners, and follow-up dates in the team's Testing Documentation. This document defines the instrumentation and rehearsal; it does not claim that live alpha testing has happened.

## Automated checks

```sh
python manage.py test apps.users apps.wardrobe apps.outfits apps.fittings apps.usage --settings=config.test_settings --noinput
python manage.py makemigrations --check --dry-run --settings=config.test_settings
python -m pytest -q
```

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug
./gradlew --no-daemon --max-workers=1 lintDebug
```

Validation on November 24, 2026:

| Check | Result |
| --- | --- |
| Django regression suite | 268 passed on the final code before PR publication |
| Final affected Django tests (`apps.usage apps.outfits.test_chat`) | 39 passed |
| Backend pytest | 321 passed, 1 skipped |
| Migration consistency | No changes detected |
| Android JVM unit tests | 414 passed; a network timeout test passed on the final full rerun |
| Debug app and instrumentation APKs | Built successfully |
| Emulator `UsageDisplayTest` | 1 passed; image completion fires after drawing and once across recomposition |
| Android lint | Passed with `--max-workers=1`: 0 errors, 38 warnings, 1 hint |

No paid fitting generation or production deployment was performed as part of automated verification. Run the rehearsal above against the deployed backend before the alpha test.
