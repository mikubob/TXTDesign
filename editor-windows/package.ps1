param([string]$Version = '1.8.0', [string]$Nsis)
$ErrorActionPreference = 'Stop'
# jpackage / NSIS 与内层 build.ps1 都会往 stderr 写进度与警告，Windows PowerShell 5.1 在 Stop 下
# 会把它们升级成 NativeCommandError；外部程序统一只看退出码。
function Invoke-Native([string]$command, [object[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $command @arguments 2>&1 | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { Write-Host $_.ToString() } else { Write-Host $_ } } } finally { $ErrorActionPreference = $previous }
}
Push-Location $PSScriptRoot
try {
    if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw '版本必须是 major.minor.patch，例如 1.1.0' }
    if (!(Get-Command jpackage -ErrorAction SilentlyContinue)) { throw '缺少 jpackage；需要 Temurin JDK 21 x64' }
    if (!$Nsis) {
        $command = Get-Command makensis.exe -ErrorAction SilentlyContinue
        $Nsis = if ($command) { $command.Source } else { Join-Path "${env:ProgramFiles(x86)}" 'NSIS/makensis.exe' }
    }
    if (!(Test-Path -LiteralPath $Nsis -PathType Leaf)) { throw '缺少 makensis.exe；请安装 NSIS 3.13 或指定 -Nsis' }
    $Nsis = (Resolve-Path -LiteralPath $Nsis).Path
    & ./build.ps1
    $packageRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build/package'))
    $workspaceBuild = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))
    if ((Split-Path -Parent $packageRoot) -ne $workspaceBuild) { throw '打包目录超出 build' }
    if (Test-Path -LiteralPath $packageRoot) {
        foreach ($path in @($workspaceBuild, $packageRoot)) {
            if ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw '打包目录不能指向其他位置'
            }
        }
        Remove-Item -LiteralPath $packageRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force build/package/input,build/package/image,build/installer | Out-Null
    Copy-Item build/txteditor-windows.jar build/package/input/
    # 应用只读写 UTF-8，不需要 jdk.charsets 与 jdk.localedata，安装包因此小约 11 MB
    Invoke-Native 'jpackage' @('--type', 'app-image', '--name', 'TXTDesign', '--app-version', $Version,
        '--vendor', 'mikubob', '--description', 'Offline UTF-8 text editor',
        '--input', 'build/package/input', '--main-jar', 'txteditor-windows.jar',
        '--add-modules', 'java.desktop,java.logging',
        '--dest', 'build/package/image')
    if ($LASTEXITCODE -ne 0) { throw 'Windows 应用镜像打包失败' }
    $image = Join-Path $packageRoot 'image/TXTDesign'
    Copy-Item THIRD-PARTY-NOTICES.md $image
    $license = Join-Path $packageRoot 'installer-notices.txt'
    [IO.File]::WriteAllText($license, [IO.File]::ReadAllText((Join-Path $image 'THIRD-PARTY-NOTICES.md')), (New-Object Text.UTF8Encoding($true)))
    $uninstall = Join-Path $packageRoot 'uninstall.nsh'
    $entries = Get-ChildItem -LiteralPath $image -Recurse -Force
    $prefix = $image.Length + 1
    $commands = @(
        $entries | Where-Object { !$_.PSIsContainer } | ForEach-Object {
            'Delete "$INSTDIR\' + $_.FullName.Substring($prefix).Replace('$', '$$') + '"'
        }
        $entries | Where-Object PSIsContainer | Sort-Object { $_.FullName.Length } -Descending | ForEach-Object {
            'RMDir "$INSTDIR\' + $_.FullName.Substring($prefix).Replace('$', '$$') + '"'
        }
    )
    [IO.File]::WriteAllLines($uninstall, [string[]]$commands, (New-Object Text.UTF8Encoding($true)))
    $installer = Join-Path $PSScriptRoot "build/installer/TXTDesign-$Version-Setup-win-x64.exe"
    if (Test-Path -LiteralPath $installer) { Remove-Item -LiteralPath $installer -Force }
    Invoke-Native $Nsis @('/V3', '/INPUTCHARSET', 'UTF8', "/DAPP_VERSION=$Version",
        "/DAPP_DIR=$image", "/DOUTPUT_EXE=$installer", "/DLICENSE_FILE=$license", "/DUNINSTALL_FILES=$uninstall", 'installer.nsi')
    if ($LASTEXITCODE -ne 0) { throw 'NSIS 安装器打包失败' }
    if (!(Test-Path -LiteralPath $installer) -or (Get-Item -LiteralPath $installer).Length -eq 0) {
        throw 'Windows 安装器未生成'
    }
    Write-Host "Installer: $installer"
} finally { Pop-Location }
