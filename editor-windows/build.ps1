param([switch]$GuiTest, [switch]$Run)
$ErrorActionPreference = 'Stop'
# javac / java / jar 会把警告写到 stderr，Windows PowerShell 5.1 在 Stop 下会把它们升级成
# NativeCommandError；外部程序统一只看退出码。
function Invoke-Native([string]$command, [object[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $command @arguments 2>&1 | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { Write-Host $_.ToString() } else { Write-Host $_ } } } finally { $ErrorActionPreference = $previous }
}
Push-Location $PSScriptRoot
try {
    $flatlaf = 'libs/flatlaf-3.7.2.jar'
    if (!(Test-Path $flatlaf)) { throw '缺少 libs/flatlaf-3.7.2.jar；下载方式见 libs/README.md' }
    if ((Get-FileHash $flatlaf -Algorithm SHA256).Hash -ne '917AFF3963C88D797D0FD9B9CCBD70F7681C101DF9D11C59E2BC7A3A6C0FABF4') {
        throw 'FlatLaf 依赖校验失败，请按 libs/README.md 重新下载'
    }
    # Compile the shared sources read-only; all outputs belong to this module.
    $buildRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))
    foreach ($name in @('classes', 'test-classes', 'core-classes', 'core-test-classes', 'flatlaf')) {
        $target = [IO.Path]::GetFullPath((Join-Path $buildRoot $name))
        if (!$target.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar)) { throw '构建清理目录越界' }
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
        New-Item -ItemType Directory -Path $target | Out-Null
    }
    $coreSources = @(Get-ChildItem ../editor-core/src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    Invoke-Native 'javac' (@('--release', '8', '-encoding', 'UTF-8', '-d', 'build/core-classes') + $coreSources)
    if ($LASTEXITCODE -ne 0) { throw '共享核心编译失败' }
    $coreTests = @(Get-ChildItem ../editor-core/src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
    Invoke-Native 'javac' (@('--release', '8', '-encoding', 'UTF-8', '-cp', 'build/core-classes', '-d', 'build/core-test-classes') + $coreTests)
    if ($LASTEXITCODE -ne 0) { throw '共享核心测试编译失败' }
    Invoke-Native 'java' @('-ea', '-cp', 'build/core-classes;build/core-test-classes', 'txteditor.core.CoreTest')
    if ($LASTEXITCODE -ne 0) { throw '共享核心测试失败' }
    $mainSources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    Invoke-Native 'javac' (@('--release', '8', '-encoding', 'UTF-8', '-cp', "build/core-classes;$flatlaf", '-d', 'build/classes') + $mainSources)
    if ($LASTEXITCODE -ne 0) { throw '桌面端编译失败' }
    $testSources = @(Get-ChildItem src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
    Invoke-Native 'javac' (@('--release', '8', '-encoding', 'UTF-8', '-cp', "build/classes;build/core-classes;$flatlaf", '-d', 'build/test-classes') + $testSources)
    if ($LASTEXITCODE -ne 0) { throw '桌面端测试编译失败' }
    $testClasspath = "build/classes;build/test-classes;build/core-classes;$flatlaf"
    Invoke-Native 'java' @('-ea', '-Djava.awt.headless=true', '-cp', $testClasspath, 'txteditor.windows.DesktopTest')
    if ($LASTEXITCODE -ne 0) { throw '桌面适配与文件测试失败' }
    Push-Location build/flatlaf
    try {
        Invoke-Native 'jar' @('xf', "../../$flatlaf")
        if ($LASTEXITCODE -ne 0) { throw 'FlatLaf 解包失败' }
        # 应用清单替换依赖清单：jar cfm 的 -m 会覆盖输入目录里的 META-INF/MANIFEST.MF，不必先删文件。
    } finally { Pop-Location }
    @('Manifest-Version: 1.0', 'Main-Class: txteditor.windows.EditorWindow', 'Multi-Release: true', '') | Set-Content -Encoding Ascii build/manifest.mf
    Invoke-Native 'jar' @('cfm', 'build/txteditor-windows.jar', 'build/manifest.mf', '-C', 'build/classes', '.', '-C', 'build/core-classes', '.', '-C', 'build/flatlaf', '.', '-C', '.', 'THIRD-PARTY-NOTICES.md')
    if ($LASTEXITCODE -ne 0) { throw '桌面打包失败' }
    if ($GuiTest) {
        Invoke-Native 'java' @('-ea', '-cp', $testClasspath, 'txteditor.windows.GuiTest')
        if ($LASTEXITCODE -ne 0) { throw '桌面交互测试失败' }
    }
    if ($Run) { Invoke-Native 'java' @('-jar', 'build/txteditor-windows.jar') }
} finally { Pop-Location }
