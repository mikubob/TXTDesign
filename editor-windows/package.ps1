param([string]$Version = '1.2.0')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw '版本必须是 major.minor.patch，例如 1.1.0' }
    foreach ($tool in @('jpackage', 'candle', 'light')) {
        if (!(Get-Command $tool -ErrorAction SilentlyContinue)) { throw "缺少 $tool；需要 JDK 21 和 WiX 3.14.1，参见 README.md" }
    }
    & ./build.ps1
    $packageRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build/package'))
    if (Test-Path -LiteralPath $packageRoot) { Remove-Item -LiteralPath $packageRoot -Recurse -Force }
    New-Item -ItemType Directory -Force build/package/input,build/package/image,build/installer | Out-Null
    Copy-Item build/txteditor-windows.jar build/package/input/
    # 应用只读写 UTF-8，不需要 jdk.charsets 与 jdk.localedata，安装包因此小约 11 MB
    & jpackage --type app-image --name TXTDesign --app-version $Version `
        --vendor mikubob --description 'Offline UTF-8 text editor' `
        --input build/package/input --main-jar txteditor-windows.jar `
        --add-modules java.desktop,java.logging `
        --dest build/package/image
    if ($LASTEXITCODE -ne 0) { throw 'Windows 应用镜像打包失败' }
    $installer = Join-Path $PSScriptRoot "build/installer/TXTDesign-$Version.exe"
    if (Test-Path -LiteralPath $installer) { Remove-Item -LiteralPath $installer -Force }
    & jpackage --type exe --name TXTDesign --app-version $Version --vendor mikubob `
        --app-image build/package/image/TXTDesign --dest build/installer `
        --win-per-user-install --win-menu --win-shortcut --win-dir-chooser `
        --win-upgrade-uuid 512dd58e-11d5-4b2d-a6d7-8309fcfe7411
    if ($LASTEXITCODE -ne 0) { throw 'Windows EXE 安装器打包失败' }
    if (!(Test-Path -LiteralPath $installer) -or (Get-Item -LiteralPath $installer).Length -eq 0) {
        throw 'Windows 安装器未生成'
    }
    Write-Host "Installer: $installer"
} finally { Pop-Location }
