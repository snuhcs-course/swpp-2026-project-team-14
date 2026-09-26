param(
    [ValidateSet('build', 'run', 'test')]
    [string]$Action = 'build',
    [string]$JavaHome = '',
    [string]$AndroidSdk = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    $candidate = Get-ChildItem 'C:/Program Files/Java' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($candidate) { $JavaHome = $candidate.FullName }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Pass -JavaHome with a JDK 17 or 21 directory.'
}
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not (Test-Path -LiteralPath $AndroidSdk)) { throw 'Android SDK not found; pass -AndroidSdk.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:GRADLE_USER_HOME = Join-Path $repoRoot '.local/gradle'
$env:ANDROID_USER_HOME = Join-Path $repoRoot '.local/android-user'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
$debugKey = Join-Path $repoRoot '.local/android-debug.keystore'
if (-not (Test-Path -LiteralPath $debugKey)) {
    & (Join-Path $JavaHome 'bin/keytool.exe') -genkeypair -keystore $debugKey -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE -ne 0) { throw 'Local debug key creation failed.' }
}
$androidProject = Join-Path $PSScriptRoot 'android'
Set-Content -LiteralPath (Join-Path $androidProject 'local.properties') -Value "sdk.dir=$($AndroidSdk.Replace('\', '/').Replace(':', '\:'))" -Encoding ascii
$tasks = @(switch ($Action) {
    'build' { @('assembleDebug', 'lintDebug') }
    'run' { @('installDebug') }
    'test' { @('assembleDebug', 'assembleDebugAndroidTest') }
})
& (Join-Path $androidProject 'gradlew.bat') -p $androidProject @tasks --console plain
if ($LASTEXITCODE -ne 0) { throw 'Android Gradle task failed.' }
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
if ($Action -eq 'test') {
    & $adb install -r (Join-Path $androidProject 'app/build/outputs/apk/debug/app-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Debug app installation failed.' }
    & $adb install -r (Join-Path $androidProject 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Test app installation failed.' }
    $testOutput = & $adb shell am instrument -w com.stylemate.localdev.test/androidx.test.runner.AndroidJUnitRunner
    $testExitCode = $LASTEXITCODE
    $testOutput | Write-Output
    if ($testExitCode -ne 0 -or ($testOutput -join "`n") -notmatch 'OK \([1-9][0-9]* tests?\)') {
        throw 'Android instrumentation test did not pass.'
    }
}
if ($Action -eq 'run') {
    & $adb shell am start -n com.stylemate.localdev/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw 'App launch failed. Connect one emulator or device.' }
}
