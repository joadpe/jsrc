param(
    [string]$Jar = "target/jsrc.jar",
    [string]$OutputDirectory = "dist",
    [string]$TreeSitterVersion = "v0.25.9",
    [string]$TreeSitterJavaVersion = "v0.23.5"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command {
    param([string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

Require-Command git
Require-Command cl.exe
Require-Command native-image.cmd

$projectDirectory = Split-Path -Parent $PSScriptRoot
$jarPath = if ([System.IO.Path]::IsPathRooted($Jar)) { $Jar } else { Join-Path $projectDirectory $Jar }
$distPath = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $projectDirectory $OutputDirectory }
$jarPath = [System.IO.Path]::GetFullPath($jarPath)
$distPath = [System.IO.Path]::GetFullPath($distPath)

$bundleName = "jsrc-windows-x64"
$bundlePath = Join-Path $distPath $bundleName
$libraryPath = Join-Path $bundlePath "lib"
$archivePath = Join-Path $distPath "$bundleName.zip"
$workPath = Join-Path ([System.IO.Path]::GetTempPath()) "jsrc-native-$([Guid]::NewGuid())"
$smokeHome = Join-Path ([System.IO.Path]::GetTempPath()) "jsrc-smoke-$([Guid]::NewGuid())"

if (-not (Test-Path $jarPath -PathType Leaf)) {
    throw "JAR not found: $jarPath. Build it first with: mvn -B -DskipTests package"
}

try {
    if (Test-Path $bundlePath) {
        Remove-Item -Recurse -Force $bundlePath
    }
    if (Test-Path $archivePath) {
        Remove-Item -Force $archivePath
    }
    New-Item -ItemType Directory -Force -Path $workPath, $libraryPath | Out-Null

    $treeSitterSource = Join-Path $workPath "tree-sitter"
    $treeSitterJavaSource = Join-Path $workPath "tree-sitter-java"

    & git clone --depth 1 --branch $TreeSitterVersion https://github.com/tree-sitter/tree-sitter.git $treeSitterSource
    if ($LASTEXITCODE -ne 0) { throw "Failed to clone tree-sitter." }

    & git clone --depth 1 --branch $TreeSitterJavaVersion https://github.com/tree-sitter/tree-sitter-java.git $treeSitterJavaSource
    if ($LASTEXITCODE -ne 0) { throw "Failed to clone tree-sitter-java." }

    $coreSource = Join-Path $treeSitterSource "lib\src\lib.c"
    $coreInclude = Join-Path $treeSitterSource "lib\include"
    $coreInternalInclude = Join-Path $treeSitterSource "lib\src"
    $apiHeader = Join-Path $coreInclude "tree_sitter\api.h"
    $definitionPath = Join-Path $workPath "tree-sitter.def"
    $coreLibrary = Join-Path $libraryPath "tree-sitter.dll"

    $apiContent = Get-Content $apiHeader -Raw
    $symbols = @(
        [regex]::Matches($apiContent, '\b(ts_[A-Za-z0-9_]+)\s*\(') |
            ForEach-Object { $_.Groups[1].Value } |
            Sort-Object -Unique
    )
    if ($symbols.Count -eq 0) {
        throw "No Tree-sitter API symbols found in $apiHeader."
    }

    $definitionLines = @("LIBRARY tree-sitter", "EXPORTS")
    $definitionLines += $symbols | ForEach-Object { "  $_" }
    Set-Content -Path $definitionPath -Value $definitionLines -Encoding ASCII

    $coreArguments = @(
        "/nologo",
        "/LD",
        "/O2",
        "/I$coreInclude",
        "/I$coreInternalInclude",
        $coreSource,
        "/link",
        "/DEF:$definitionPath",
        "/OUT:$coreLibrary"
    )
    & cl.exe @coreArguments
    if ($LASTEXITCODE -ne 0) { throw "Failed to build tree-sitter.dll." }

    $parserSource = Join-Path $treeSitterJavaSource "src\parser.c"
    $parserInclude = Join-Path $treeSitterJavaSource "src"
    $grammarLibrary = Join-Path $libraryPath "tree-sitter-java.dll"
    $clArguments = @(
        "/nologo",
        "/LD",
        "/O2",
        "/I$parserInclude",
        $parserSource,
        "/link",
        "/OUT:$grammarLibrary"
    )
    & cl.exe @clArguments
    if ($LASTEXITCODE -ne 0) { throw "Failed to build tree-sitter-java.dll." }

    $nativeArguments = @(
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.library.path=$libraryPath",
        "-jar", $jarPath,
        "-o", (Join-Path $bundlePath "jsrc"),
        "-H:+UnlockExperimentalVMOptions",
        "-H:+SharedArenaSupport",
    )
    & native-image.cmd @nativeArguments
    if ($LASTEXITCODE -ne 0) { throw "native-image failed." }

    New-Item -ItemType Directory -Force -Path (Join-Path $smokeHome "lib") | Out-Null
    Copy-Item (Join-Path $libraryPath "*.dll") (Join-Path $smokeHome "lib")

    $previousUserProfile = $env:USERPROFILE
    $previousHome = $env:HOME
    try {
        $env:USERPROFILE = $smokeHome
        $env:HOME = $smokeHome

        & (Join-Path $bundlePath "jsrc.exe") describe --json | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Positive smoke test failed." }

        & (Join-Path $bundlePath "jsrc.exe") definitely-not-a-command *> $null
        if ($LASTEXITCODE -eq 0) { throw "Negative smoke test unexpectedly succeeded." }
    }
    finally {
        $env:USERPROFILE = $previousUserProfile
        $env:HOME = $previousHome
    }

    Compress-Archive -Path $bundlePath -DestinationPath $archivePath
    Write-Host "Created $archivePath"
}
finally {
    Remove-Item -Recurse -Force $workPath, $smokeHome -ErrorAction SilentlyContinue
}
