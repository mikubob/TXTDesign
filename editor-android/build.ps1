param(
    [string]$Sdk = $env:ANDROID_HOME,
    [switch]$Install,
    [switch]$Test,
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
if (!$Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!$env:JAVA_HOME) { throw '请将 JAVA_HOME 指向 JDK 17 或 21，参见 README.md。' }
function Run([string]$command, [string[]]$arguments) {
    # javac / gradle 把 "-source 8 已过时" 这类警告写到 stderr，Stop 模式会把它当致命错误；真实失败靠退出码判断。
    $ErrorActionPreference = 'Continue'
    & $command @arguments
    if ($LASTEXITCODE -ne 0) { throw "命令失败 ($LASTEXITCODE): $command" }
}
$env:ANDROID_HOME = $Sdk
[IO.File]::WriteAllText((Join-Path $project 'local.properties'), 'sdk.dir=' + $Sdk.Replace('\', '/').Replace(':', '\:') + "`n")
$key = Join-Path $project 'signing\debug.keystore'
if (!(Test-Path -LiteralPath $key)) {
    New-Item -ItemType Directory -Force (Split-Path $key) | Out-Null
    $previous = Join-Path $project 'build\debug.keystore'
    if (Test-Path -LiteralPath $previous) { Copy-Item -LiteralPath $previous -Destination $key }
    else {
        Run "$env:JAVA_HOME\bin\keytool.exe" @('-genkeypair', '-keystore', $key, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US')
    }
}
$tasks = @('assembleRelease', '--console=plain')
if ($Test) { $tasks += @('adapterCheck', 'lintDebug') }
Run "$project\gradlew.bat" (@('-p', $project) + $tasks)
$apk = @(Get-ChildItem -LiteralPath "$project\build\outputs\apk\release" -Filter '*.apk')
if ($apk.Count -ne 1) { throw '未找到唯一的调试 APK。' }
Copy-Item -LiteralPath $apk[0].FullName -Destination "$project\build\txteditor-android.apk" -Force
Remove-Item -LiteralPath "$project\build\txteditor-android.apk.idsig" -ErrorAction SilentlyContinue
Run "$Sdk\build-tools\36.0.0\apksigner.bat" @('verify', '--verbose', '--print-certs', "$project\build\txteditor-android.apk")
Write-Host "APK: $project\build\txteditor-android.apk"
if ($Install) {
    $target = @()
    if ($Serial) { $target = @('-s', $Serial) }
    Run "$Sdk\platform-tools\adb.exe" ($target + @('install', '-r', "$project\build\txteditor-android.apk"))
    Run "$Sdk\platform-tools\adb.exe" ($target + @('shell', 'am', 'start', '-n', 'txteditor.android/.MainActivity'))
}
