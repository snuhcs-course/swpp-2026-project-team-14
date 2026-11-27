# App stability telemetry

Issue: [#119](https://github.com/snuhcs-course/swpp-2026-project-team-14/issues/119). Backend migration and app distribution are both required.

Collection starts in `StabilityApplication.onCreate`, including before login. No external analytics account, SDK or key is required.

## Events

| Kind / source | Trigger | Diagnostic information |
| --- | --- | --- |
| `active` / `lifecycle` | Activity resumes, or foreground use crosses a KST date boundary | Random installation UUID, process-session UUID, time, app version and Android SDK level |
| `crash` / `java` | Uncaught Java/Kotlin exception on any thread | Exception class and up to 20 code frames, synchronously persisted before delegating to Android's existing crash handler |
| `crash` / `os_exit` | Next launch finds an Android 11+ Java/native crash exit | OS reason/status and original version/session; same ID as the Java report, preventing duplicate counting |
| `anr` / `os_exit` | Next launch finds an Android 11+ `REASON_ANR` process exit | Confirmed ANR termination, exit time, foreground/visible state, and sanitized main-thread code frames when retained by the OS |
| `stall` / `watchdog` | Foreground main-looper heartbeat is blocked at least five seconds | Monotonic blocked duration and main-thread code frames; one report until recovery |

The watchdog suppresses background/debugger probes and uses uptime to exclude deep sleep. A suspected stall does **not** prove Android displayed an ANR dialog. Android 8–10 support stalls and Java crashes; confirmed ANR/native-exit recovery requires Android 11+. Recovered ANRs without an ANR termination may appear only as stalls.

Reports can be lost if the app is never reopened, storage fails, app data is cleared, or the OS overwrites its history. OOM/native crashes may prevent Java stack capture; OS recovery provides limited fallback metadata. These limitations also apply when interpreting an apparently low ANR rate.

References: [Android ANRs](https://developer.android.com/topic/performance/vitals/anr), [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo), [ActivityManager](https://developer.android.com/reference/android/app/ActivityManager).

## Storage and transport

1. App-private `noBackupFilesDir/stability/events.json` retains the newest 200 diagnostics, five per batch. Random installation identity resets when app data is cleared and is excluded from backup.
2. After login/resume, or when another diagnostic is queued, the existing authenticated WorkManager uploader sends `POST /api/usage/stability/`, body `{"events": [...]}`. A fatal crash itself does not attempt networking. Failed uploads retry with exponential backoff; only acknowledged IDs are removed.
3. MySQL table **`usage_stabilityevent`** stores immutable, deduplicated reports. They belong to the installation, not an account: login supplies upload authorization only. Pre-login and post-logout reports are never attributed to the next user. There is no public read/list API.

Fields: `id`, `installation_id`, `session_id`, `kind`, `source`, `occurred_at`, `app_version`, `sdk_int`, `foreground`, `exception_type`, `frames`, `duration_ms`, `exit_reason`, `exit_status`. The server adds `received_at`, validates all fields and limits bytes/batches. `foreground` on OS exits includes foreground/visible process importance.

Exception messages, raw ANR traces/native tombstones, thread names, photos/screenshots, chat/user input, credentials, filesystem paths and hardware IDs are excluded. Frames contain code symbols and source filenames/line numbers only. The random installation UUID links one installation across logins. Restrict DB/CSV access to the testing team and include diagnostic collection in the app's data-collection disclosure.

## View and export

Run within the deployed backend environment using its normal database configuration:

```sh
python manage.py migrate
python manage.py export_stability --since 2026-11-29T15:00:00Z --until 2026-12-06T15:00:00Z > stability-events.csv
python manage.py export_stability --summary --since 2026-11-29T15:00:00Z --until 2026-12-06T15:00:00Z > stability-daily.csv
```

The first CSV contains individual diagnostics and code locations; the second contains daily KST rates and denominators. Use complete KST days (`15:00:00Z` inclusive/exclusive boundaries), let testers reopen/upload, and re-export after late arrivals.

- **Observed ANR rate:** distinct active Android 11+ installations with a foreground/visible confirmed ANR exit that day / distinct active Android 11+ installations that day × 100. Target: **below 1%**. Unsupported Android versions are excluded from this denominator; with none supported the rate is blank, not zero.
- **Observed stall/crash rates:** distinct active installations with a foreground stall/crash that day / all active installations that day × 100. Multiple events/sessions do not inflate affected-installation counts. Background reports remain in the raw CSV.
- `anr_without_active_day` exposes confirmed affected installations missing matching supported activity rather than silently using another denominator.

These are observed installation/day rates, **not Google Play Android vitals**. They are lower-bound observations subject to restart/history/upload limitations. OS timestamps are termination times, not exact initial hang times. Include sample size, Android coverage, confirmed ANR counts and stall counts when assessing the goal; absence of arriving reports does not demonstrate <1%.

## Alpha rehearsal

Deploy backend/migration first, then distribute the APK. Use a disposable test device and account.

1. Log in, use/reopen the app, wait for upload and verify an `active` row in MySQL/export.
2. On an Android 11+ emulator run `adb shell am crash com.swpp.stylemate`, reopen and log in if needed. Verify an OS crash report with the original session/version; another relaunch must not duplicate it. This checks OS recovery, not Java-handler stack capture.
3. For Java-handler capture, temporarily add an uncaught exception in a disposable debug build, with debugger exception interception disabled. Reopen and verify the exception class/code location, no exception message, and one crash despite OS recovery. Do not ship an intentional-crash control.
4. Temporarily block the debug app's main thread for seven seconds with the debugger detached, then recover. Verify a `stall`, separately from a confirmed ANR. For an OS ANR, provoke a real input-dispatch timeout, choose close if Android offers the dialog, reopen and verify `anr`. Restore the unmodified APK afterward.
5. Repeat offline, then reopen/login online: reports must upload once. Test before login and across logout/login; diagnostics must contain no account/user content.
6. Export both CSVs, match performed actions/time/version, inspect eligible installation counts and record results/follow-up in Testing Documentation.

CI covers handler persistence/delegation, sanitization, recovery IDs, watchdog suppression/recovery, queue retries, authenticated validation and rates/CSV. Device tests cover the installed Application, a briefly blocked real main looper and permission-free exit-history access. The deployed-device rehearsal remains a separate release check.
