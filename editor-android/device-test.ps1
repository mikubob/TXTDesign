param([string]$Sdk = $env:ANDROID_HOME, [string]$Serial, [switch]$TestInstance)
$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$project = $PSScriptRoot
$build = Join-Path $project 'build'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
function Run([string]$command, [string[]]$arguments) {
    & $command @arguments
    if ($LASTEXITCODE -ne 0) { throw "命令失败 ($LASTEXITCODE): $command" }
}
$devices = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_.Line -split '\s+')[0] })
if (!$Serial) {
    if ($devices.Count -ne 1) { throw '请用 -Serial 指定一台测试模拟器。' }
    $Serial = $devices[0]
}
if ($Serial -notin $devices) { throw "设备未连接或未授权：$Serial" }
if (!$Serial.StartsWith('emulator-') -and !$TestInstance) { throw '设备测试会覆盖草稿；实体测试机请明确使用 -TestInstance。' }
& "$project\build.ps1" -Sdk $Sdk -Test
if (!$?) { throw '主 APK 构建失败。' }
Run "$project\gradlew.bat" @('-p', $project, 'assembleDebugAndroidTest', '--console=plain')
$tests = @(Get-ChildItem -LiteralPath "$build\outputs\apk\androidTest\debug" -Filter '*.apk')
if ($tests.Count -ne 1) { throw '未找到唯一的设备测试 APK。' }
Copy-Item -LiteralPath $tests[0].FullName -Destination "$build\txteditor-tests.apk" -Force
Remove-Item -LiteralPath "$build\txteditor-tests.apk.idsig" -ErrorAction SilentlyContinue
Run $adb @('-s', $Serial, 'install', '-r', "$build\txteditor-debug.apk")
Run $adb @('-s', $Serial, 'install', '-r', "$build\txteditor-tests.apk")
foreach ($restore in @('false', 'true')) {
    Run $adb @('-s', $Serial, 'shell', 'am', 'force-stop', 'txteditor.android')
    $output = & $adb -s $Serial shell am instrument -w -e restore $restore txteditor.android.tests/txteditor.android.DeviceTests
    $output | Tee-Object -FilePath "$build\device-test-$restore.txt"
    if ($LASTEXITCODE -ne 0 -or ($output -join [Environment]::NewLine) -notmatch 'DeviceTests passed:') { throw '设备回归失败，请检查输出和 adb logcat。' }
}
