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
    New-Item -ItemType Directory -Force build/classes,build/test-classes | Out-Null
    $mainSources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    Invoke-Native 'javac' (@('--release', '8', '-encoding', 'UTF-8', '-d', 'build/classes') + $mainSources)
    if ($LASTEXITCODE -ne 0) { throw '核心编译失败' }
    $testSources = @(Get-ChildItem src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
    Invoke-Native 'javac' (@('--release', '8', '-encoding', 'UTF-8', '-cp', 'build/classes', '-d', 'build/test-classes') + $testSources)
    if ($LASTEXITCODE -ne 0) { throw '核心测试编译失败' }
    Invoke-Native 'java' @('-ea', '-cp', 'build/classes;build/test-classes', 'txteditor.core.CoreTest')
    if ($LASTEXITCODE -ne 0) { throw '核心测试失败' }
    Invoke-Native 'java' @('-ea', '-cp', 'build/classes;build/test-classes', 'txteditor.core.TextTest')
    if ($LASTEXITCODE -ne 0) { throw '高亮与 Markdown 测试失败' }
    Invoke-Native 'jar' @('cf', 'build/editor-core.jar', '-C', 'build/classes', '.')
    if ($LASTEXITCODE -ne 0) { throw '核心打包失败' }
} finally { Pop-Location }
