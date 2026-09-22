param(
    [string]$Jar = "target/jsrc.jar",
    [string]$OutputDirectory = "dist",
    [string]$TreeSitterCommit = "a467ea8502d95562171f97953a6dc5b2a8622609",
    [string]$TreeSitterJavaCommit = "94703d5a6bed02b98e438d7cad1136c01a60ba2c"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command {
    param([string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Import-MsvcEnvironment {
    if (Get-Command cl.exe -ErrorAction SilentlyContinue) {
        return
    }

    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere -PathType Leaf)) {
        throw "vswhere.exe not found. Install Visual Studio Build Tools with Desktop development with C++."
    }

    $installationPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $installationPath) {
        throw "Visual Studio C++ build tools were not found."
    }

    $developerCommand = Join-Path $installationPath "Common7\Tools\VsDevCmd.bat"
    $command = "`"$developerCommand`" -no_logo -arch=x64 -host_arch=x64 >nul && set"
    & $env:ComSpec /d /s /c $command | ForEach-Object {
        $separator = $_.IndexOf("=")
        if ($separator -gt 0) {
            $name = $_.Substring(0, $separator)
            $value = $_.Substring($separator + 1)
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

function Checkout-Repository {
    param(
        [string]$RepositoryUrl,
        [string]$Commit,
        [string]$Destination
    )

    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    & git -C $Destination init -q
    & git -C $Destination remote add origin $RepositoryUrl
    & git -C $Destination fetch -q --depth 1 origin $Commit
    & git -C $Destination checkout -q --detach FETCH_HEAD
    if ($LASTEXITCODE -ne 0) { throw "Failed to check out $RepositoryUrl at $Commit." }

    $actualCommit = (& git -C $Destination rev-parse HEAD).Trim()
    if ($actualCommit -ne $Commit) {
        throw "Expected $Commit from $RepositoryUrl, got $actualCommit."
    }
}

Require-Command git
Require-Command native-image.cmd
Import-MsvcEnvironment
Require-Command cl.exe

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
$smokeHome = Join-Path $workPath "home"
$smokeProject = Join-Path $workPath "project"
$smokeExtract = Join-Path $workPath "extracted"
$savedLibraryPath = Join-Path $workPath "build-libs"
$libraryMoved = $false

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
    Checkout-Repository https://github.com/tree-sitter/tree-sitter.git $TreeSitterCommit $treeSitterSource
    Checkout-Repository https://github.com/tree-sitter/tree-sitter-java.git $TreeSitterJavaCommit $treeSitterJavaSource

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
        "/std:c11",
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
    $grammarArguments = @(
        "/nologo",
        "/LD",
        "/O2",
        "/std:c11",
        "/I$parserInclude",
        $parserSource,
        "/link",
        "/OUT:$grammarLibrary"
    )
    & cl.exe @grammarArguments
    if ($LASTEXITCODE -ne 0) { throw "Failed to build tree-sitter-java.dll." }

    $nativeArguments = @(
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.library.path=$libraryPath",
        "-jar", $jarPath,
        "-o", (Join-Path $bundlePath "jsrc"),
        "-H:+UnlockExperimentalVMOptions",
        "-H:+SharedArenaSupport"
    )
    & native-image.cmd @nativeArguments
    if ($LASTEXITCODE -ne 0) { throw "native-image failed." }

    Compress-Archive -Path $bundlePath -DestinationPath $archivePath
    Expand-Archive -Path $archivePath -DestinationPath $smokeExtract

    New-Item -ItemType Directory -Force -Path (Join-Path $smokeHome "lib") | Out-Null
    Copy-Item (Join-Path $smokeExtract "$bundleName\lib\*.dll") (Join-Path $smokeHome "lib")

    $javaSourceDirectory = Join-Path $smokeProject "src\main\java\example"
    New-Item -ItemType Directory -Force -Path $javaSourceDirectory | Out-Null
    @'
package example;

public final class Hello {
    public String message() {
        return "hello";
    }
}
'@ | Set-Content -Path (Join-Path $javaSourceDirectory "Hello.java") -Encoding ASCII

    Move-Item $libraryPath $savedLibraryPath
    $libraryMoved = $true

    $previousUserProfile = $env:USERPROFILE
    $previousHome = $env:HOME
    try {
        $env:USERPROFILE = $smokeHome
        $env:HOME = $smokeHome
        $smokeBinary = Join-Path $smokeExtract "$bundleName\jsrc.exe"

        & $smokeBinary -d $smokeProject index | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Functional smoke index failed." }

        $overviewOutput = (& $smokeBinary -d $smokeProject overview --json | Out-String)
        if ($LASTEXITCODE -ne 0 -or $overviewOutput -notmatch '"totalFiles"') {
            throw "Functional smoke overview failed."
        }

        $readOutput = (& $smokeBinary -d $smokeProject read Hello --json | Out-String)
        if ($LASTEXITCODE -ne 0 -or $readOutput -notmatch "Hello") {
            throw "Functional smoke read failed."
        }

        & $smokeBinary definitely-not-a-command *> $null
        if ($LASTEXITCODE -eq 0) { throw "Negative smoke test unexpectedly succeeded." }
    }
    finally {
        $env:USERPROFILE = $previousUserProfile
        $env:HOME = $previousHome
    }

    Move-Item $savedLibraryPath $libraryPath
    $libraryMoved = $false
    Write-Host "Created $archivePath"
}
finally {
    if ($libraryMoved -and (Test-Path $savedLibraryPath) -and -not (Test-Path $libraryPath)) {
        Move-Item $savedLibraryPath $libraryPath
    }
    Remove-Item -Recurse -Force $workPath -ErrorAction SilentlyContinue
}
