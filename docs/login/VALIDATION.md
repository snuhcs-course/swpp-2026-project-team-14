# Login-required transition: validation

Branch: `main-merge-1017`. Work was performed in the existing checkout without changing branches.

## Completed checks

| Check | Result |
| --- | --- |
| Django account, wardrobe and outfit suites | 110 passed |
| Backend pytest | 142 passed |
| Android JVM unit tests | 95 passed; 0 failures, errors or skipped tests |
| Android debug APK (`assembleDebug`) | Passed |
| Android instrumentation Kotlin compilation (`compileDebugAndroidTestKotlin`) | Passed |
| Android lint (`lintDebug`) | 0 errors, 0 fatal issues, 32 warnings |
| Django migration drift (`makemigrations --check --dry-run`) | No changes detected |
| Whitespace (`git diff --check`) | Passed |

Django command: `python manage.py test apps.users apps.wardrobe apps.outfits --settings=config.test_settings --noinput`.
Backend command: `python -m pytest`.
Android tasks: `:app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug`.

The API tests verify that missing/invalid/revoked tokens are rejected before analysis providers run, guest-only routes are removed, and shared options remain accessible with authentication. Existing authenticated legacy-data import remains available and is included in the passing Django and JVM suites. Client tests cover account separation, authenticated requests, expiry handling and onboarding cache behavior.

APK: `frontend/app/build/outputs/apk/debug/app-debug.apk` (31,722,224 bytes).
Lint report: `frontend/app/build/reports/lint-results-debug.html`.
JVM test report: `frontend/app/build/reports/tests/testDebugUnitTest/index.html`.

## Environment limits

Device/emulator UI tests were not executed. ADB could not initialize its settings directory (`Cannot mkdir \.android: Permission denied`), and no ADB server was listening. A full instrumentation APK assembly attempt also encountered access denied on the existing Gradle cache JAR `androidx.collection:collection-jvm:1.5.0`. Instrumentation Kotlin compilation passed, but this does not establish a passing device test run or a successfully packaged instrumentation APK.

External model/API calls are replaced by test doubles. Actual Gemini responses, production MySQL and Azure deployment were not exercised. No production migrations, account-data deletion, commit or push were performed.

The current API contract and user flow are documented in [README.md](README.md).
