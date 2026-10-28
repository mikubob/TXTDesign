param([string]$Sdk = $env:ANDROID_HOME, [string]$Serial, [switch]$TestInstance)
$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$project = $PSScriptRoot
$build = Join-Path $project 'build'
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
function Run([string]$command, [string[]]$arguments) {
    # javac / gradle 把 "-source 8 已过时" 这类警告写到 stderr，Stop 模式会把它当致命错误；真实失败靠退出码判断。
    $ErrorActionPreference = 'Continue'
    & $command @arguments
    if ($LASTEXITCODE -ne 0) { throw "命令失败 ($LASTEXITCODE): $command" }
}
# adb 会把 "daemon not running" 写到 stderr，Stop 模式下会被当成致命错误，这几次调用单独放宽。
$ErrorActionPreference = 'Continue'
$devices = @(& $adb devices 2>&1 | Select-String '^\S+\s+device$' | ForEach-Object { ($_.Line -split '\s+')[0] })
$ErrorActionPreference = 'Stop'
if (!$Serial) {
    if ($devices.Count -ne 1) { throw '请用 -Serial 指定一台测试模拟器。' }
    $Serial = $devices[0]
}
if ($Serial -notin $devices) { throw "设备未连接或未授权：$Serial" }
if (!$Serial.StartsWith('emulator-') -and !$TestInstance) { throw '设备测试会覆盖草稿；实体测试机请明确使用 -TestInstance。' }
& "$project\build.ps1" -Sdk $Sdk -Test
if (!$?) { throw '主 APK 构建失败。' }
Run "$project\gradlew.bat" @('-p', $project, 'assembleDebug', 'assembleDebugAndroidTest', '--console=plain')
# 设备测试装调试包：DeviceTests 反射取 MainActivity 的私有字段，release 包被 R8 改名后取不到。
$app = @(Get-ChildItem -LiteralPath "$build\outputs\apk\debug" -Filter '*.apk')
if ($app.Count -ne 1) { throw '未找到唯一的调试 APK。' }
$tests = @(Get-ChildItem -LiteralPath "$build\outputs\apk\androidTest\debug" -Filter '*.apk')
if ($tests.Count -ne 1) { throw '未找到唯一的设备测试 APK。' }
Copy-Item -LiteralPath $tests[0].FullName -Destination "$build\txteditor-tests.apk" -Force
Remove-Item -LiteralPath "$build\txteditor-tests.apk.idsig" -ErrorAction SilentlyContinue
# adb install 打印 Failure 时退出码仍是 0，必须看输出；换过签名（旧 debug 证书）的包要卸载后才能装。
function Install([string]$apk, [string]$package) {
    $ErrorActionPreference = 'Continue'
    $out = (& $adb -s $Serial install -r $apk 2>&1 | Out-String)
    if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        $null = & $adb -s $Serial uninstall $package 2>&1
        $out = (& $adb -s $Serial install -r $apk 2>&1 | Out-String)
    }
    if ($out -notmatch 'Success') { throw "安装失败：$apk`n$out" }
}
Install $app[0].FullName 'txteditor.android'
Install "$build\txteditor-tests.apk" 'txteditor.android.tests'
foreach ($restore in @('false', 'true')) {
    Run $adb @('-s', $Serial, 'shell', 'am', 'force-stop', 'txteditor.android')
    $ErrorActionPreference = 'Continue'
    $output = & $adb -s $Serial shell am instrument -w -e restore $restore txteditor.android.tests/txteditor.android.DeviceTests
    $ErrorActionPreference = 'Stop'
    $output | Tee-Object -FilePath "$build\device-test-$restore.txt"
    if ($LASTEXITCODE -ne 0 -or ($output -join [Environment]::NewLine) -notmatch 'DeviceTests passed:') { throw '设备回归失败，请检查输出和 adb logcat。' }
}
