param([string]$Sdk = $env:ANDROID_HOME, [string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!$Serial.StartsWith('emulator-')) { throw '界面预览只在测试模拟器上执行，会替换其草稿和显示设置。' }
$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$previews = Join-Path $PSScriptRoot 'previews'
New-Item -ItemType Directory -Force $previews | Out-Null
function Run-Adb([string[]]$Arguments) {
    # adb pull 把 "1 file pulled" 写到 stderr，Stop 模式会当致命错误；真实失败靠退出码判断。
    $ErrorActionPreference = 'Continue'
    & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB 失败：$Arguments" }
}
function Capture([string]$Name, [string]$Search = 'false', [string]$Keyboard = 'false') {
    Run-Adb @('shell', 'am', 'force-stop', 'txteditor.android')
    Start-Sleep -Milliseconds 700
    $output = & $adb -s $Serial shell am instrument -w -e preview $Name -e search $Search -e keyboard $Keyboard txteditor.android.tests/txteditor.android.DeviceTests
    $output | Tee-Object -FilePath (Join-Path $previews "$Name.txt")
    $result = $LASTEXITCODE
    Run-Adb @('pull', "/sdcard/Android/data/txteditor.android/files/previews/$Name.png", (Join-Path $previews "$Name.png"))
    if ($result -ne 0 -or ($output -join [Environment]::NewLine) -notmatch 'DeviceTests passed:') { throw "界面检查失败：$Name" }
}
$font = (& $adb -s $Serial shell settings get system font_scale).Trim()
$rotation = (& $adb -s $Serial shell settings get system user_rotation).Trim()
$autoRotate = (& $adb -s $Serial shell settings get system accelerometer_rotation).Trim()
$night = (& $adb -s $Serial shell cmd uimode night) -join ''
$night = [regex]::Match($night, '(yes|no|auto|custom)').Value
if (!$night) { throw '无法读取模拟器原来的明暗设置。' }
$size = (& $adb -s $Serial shell wm size) -join ''
$override = [regex]::Match($size, 'Override size: (\d+x\d+)').Groups[1].Value
try {
    Run-Adb @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    Run-Adb @('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    Run-Adb @('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    Run-Adb @('shell', 'cmd', 'uimode', 'night', 'no')
    Capture 'light'
    foreach ($name in @('file-menu', 'leave-dialog')) {
        Run-Adb @('pull', "/sdcard/Android/data/txteditor.android/files/previews/$name.png", (Join-Path $previews "$name.png"))
    }
    Capture 'java'
    Capture 'markdown'
    Capture 'search' 'true'
    Capture 'keyboard' 'true' 'true'
    Run-Adb @('shell', 'cmd', 'uimode', 'night', 'yes')
    Capture 'dark'
    Run-Adb @('shell', 'cmd', 'uimode', 'night', 'no')
    Run-Adb @('shell', 'settings', 'put', 'system', 'user_rotation', '1')
    Capture 'landscape-keyboard' 'true' 'true'
    Run-Adb @('shell', 'wm', 'size', '840x1491')
    Capture 'small-landscape' 'true' 'true'
    # 840x1491 在 640dpi 上只有 210dp 宽：配 1.5 倍字体专门盯标题栏不被副标题撑成一列。
    Run-Adb @('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    Run-Adb @('shell', 'settings', 'put', 'system', 'font_scale', '1.5')
    Capture 'narrow-large-type'
    # 大字号还要在正常宽度下验证一次（字体仍是 1.5）。
    Run-Adb @('shell', 'wm', 'size', $(if ($override) { $override } else { 'reset' }))
    Capture 'large-type'
    Capture 'large-type-search' 'true' 'true'
} finally {
    Run-Adb @('shell', 'am', 'force-stop', 'txteditor.android')
    Run-Adb @('shell', 'wm', 'size', $(if ($override) { $override } else { 'reset' }))
    Run-Adb @('shell', 'settings', 'put', 'system', 'font_scale', $font)
    Run-Adb @('shell', 'settings', 'put', 'system', 'user_rotation', $rotation)
    Run-Adb @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', $autoRotate)
    Run-Adb @('shell', 'cmd', 'uimode', 'night', $night)
}
