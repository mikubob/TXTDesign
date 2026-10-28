$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Force build/classes,build/test-classes | Out-Null
    $mainSources = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName)
    & javac --release 8 -encoding UTF-8 -d build/classes @mainSources
    if ($LASTEXITCODE -ne 0) { throw '核心编译失败' }
    $testSources = @(Get-ChildItem src/test/java -Recurse -Filter *.java | ForEach-Object FullName)
    & javac --release 8 -encoding UTF-8 -cp build/classes -d build/test-classes @testSources
    if ($LASTEXITCODE -ne 0) { throw '核心测试编译失败' }
    & java -ea -cp 'build/classes;build/test-classes' txteditor.core.CoreTest
    if ($LASTEXITCODE -ne 0) { throw '核心测试失败' }
    & jar cf build/editor-core.jar -C build/classes .
    if ($LASTEXITCODE -ne 0) { throw '核心打包失败' }
} finally { Pop-Location }
