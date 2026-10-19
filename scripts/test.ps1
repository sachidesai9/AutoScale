# Windows PowerShell: compile and run the JUnit 5 tests. Usage: scripts\test.ps1 [unit|integration|all]
param([string]$What = "all")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
if (-not (Test-Path dist\autoscalesim-lab.jar)) { & "$PSScriptRoot\build.ps1" }
Remove-Item -Recurse -Force build\test -ErrorAction SilentlyContinue
$cp = "build\original;build\extension;lib/*;test-lib/*"
javac --release 17 -encoding UTF-8 -cp $cp -d build\test (Get-ChildItem extension\test\budgetguard\*.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "compiling tests failed" }
$tags = @()
if ($What -eq "unit") { $tags = @("--exclude-tag", "integration") }
if ($What -eq "integration") { $tags = @("--include-tag", "integration") }
java -cp "$cp;build\test" org.junit.platform.console.ConsoleLauncher execute --scan-classpath build\test @tags --details=tree --disable-banner --disable-ansi-colors --reports-dir build\test-reports
