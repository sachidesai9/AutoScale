# Windows PowerShell build: compiles the unmodified AutoScaleSim sources (Java 8 target) and the extension
# (Java 17) and packages dist\autoscalesim-lab.jar. Needs JDK 17 or newer on PATH.
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
Remove-Item -Recurse -Force build\original, build\extension, dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force build\original, build\extension, dist\lib | Out-Null
Get-ChildItem src -Recurse -Filter *.java | Resolve-Path -Relative | Set-Content -Encoding ascii build\sources-original.txt
javac --release 8 -nowarn -encoding UTF-8 -cp "lib/*" -d build\original "@build\sources-original.txt"
if ($LASTEXITCODE -ne 0) { throw "compiling the original sources failed" }
javac --release 17 -Xlint:-options -encoding UTF-8 -cp "build\original;lib/*" -d build\extension (Get-ChildItem extension\src\budgetguard\*.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "compiling the extension failed" }
Copy-Item -Recurse extension\web build\extension\web
$cp = (Get-ChildItem lib\*.jar | ForEach-Object { "lib/" + $_.Name }) -join " "
"Main-Class: budgetguard.BudgetGuard`nClass-Path: $cp`n" | Set-Content -Encoding ascii build\manifest.txt
jar --create --file dist\autoscalesim-lab.jar --manifest build\manifest.txt -C build\original . -C build\extension .
Copy-Item lib\*.jar dist\lib\
Write-Host "Built dist\autoscalesim-lab.jar"
