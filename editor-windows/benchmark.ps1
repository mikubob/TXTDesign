$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & ./build.ps1
    & java -cp 'build/core-classes;build/core-test-classes' txteditor.core.Benchmark | Tee-Object core-benchmark-results.csv
    if ($LASTEXITCODE -ne 0) { throw '核心基准失败' }
    & java -cp 'build/classes;build/test-classes;build/core-classes;libs/flatlaf-3.7.2.jar' txteditor.windows.DesktopBenchmark | Tee-Object benchmark-results.csv
    if ($LASTEXITCODE -ne 0) { throw '桌面基准失败' }
} finally { Pop-Location }
