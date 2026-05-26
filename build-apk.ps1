$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$WorkspaceRoot = Split-Path -Parent $ProjectRoot

$SdkRoot = "D:\Softwear\Android_Studio_sdk"
$JavaHome = "D:\Softwear\Android Studio\jbr"
$BuildTools = Join-Path $SdkRoot "build-tools\37.0.0"
$PlatformJar = Join-Path $SdkRoot "platforms\android-36.1\android.jar"

$LibsDir = Join-Path $ProjectRoot "libs"
$LibJars = @(Get-ChildItem -Path $LibsDir -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$Classpath = (@($PlatformJar) + $LibJars) -join ";"

$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$BuildTools;$SdkRoot\platform-tools;$env:Path"

$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$D8 = Join-Path $BuildTools "d8.bat"
$Zipalign = Join-Path $BuildTools "zipalign.exe"
$Apksigner = Join-Path $BuildTools "apksigner.bat"
$Javac = Join-Path $JavaHome "bin\javac.exe"
$JarTool = Join-Path $JavaHome "bin\jar.exe"
$Keytool = Join-Path $JavaHome "bin\keytool.exe"

foreach ($required in @($SdkRoot, $JavaHome, $Aapt2, $D8, $Zipalign, $Apksigner, $Javac, $JarTool, $Keytool, $PlatformJar)) {
    if (!(Test-Path $required)) {
        throw "Missing required path: $required"
    }
}

function Invoke-Tool {
    param(
        [string] $FilePath,
        [string[]] $Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($Arguments -join ' ')"
    }
}

$BuildDir = Join-Path $WorkspaceRoot "native-apk-build"
$CompiledDir = Join-Path $BuildDir "compiled"
$GenDir = Join-Path $BuildDir "gen"
$ClassesDir = Join-Path $BuildDir "classes"
$DexDir = Join-Path $BuildDir "dex"
$DistDir = Join-Path $ProjectRoot "dist"

Remove-Item -LiteralPath $BuildDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $CompiledDir, $GenDir, $ClassesDir, $DexDir, $DistDir | Out-Null

# Copy sources to an ASCII temp path before invoking Android tools.
$Manifest = Join-Path $ProjectRoot "app\src\main\AndroidManifest.xml"
$ResDir = Join-Path $ProjectRoot "app\src\main\res"
$JavaDir = Join-Path $ProjectRoot "app\src\main\java"
New-Item -ItemType Directory -Force -Path (Join-Path $BuildDir "app\src\main") | Out-Null
Copy-Item -LiteralPath $Manifest -Destination (Join-Path $BuildDir "app\src\main\AndroidManifest.xml") -Force
Copy-Item -LiteralPath $ResDir -Destination (Join-Path $BuildDir "app\src\main\res") -Recurse -Force
Copy-Item -LiteralPath $JavaDir -Destination (Join-Path $BuildDir "app\src\main\java") -Recurse -Force

$Manifest = Join-Path $BuildDir "app\src\main\AndroidManifest.xml"
$ResDir = Join-Path $BuildDir "app\src\main\res"
$JavaDir = Join-Path $BuildDir "app\src\main\java"
$UnsignedApk = Join-Path $BuildDir "unsigned.apk"
$UnsignedDexApk = Join-Path $BuildDir "unsigned-with-dex.apk"
$AlignedApk = Join-Path $BuildDir "aligned.apk"
$ClassesJar = Join-Path $BuildDir "classes.jar"
$SignedApk = Join-Path $BuildDir "movie-audio-listener-debug.apk"
$OutputApk = Join-Path $DistDir "movie-audio-listener-debug.apk"
$Keystore = Join-Path $WorkspaceRoot "movie-audio-debug.keystore"

$ResourceFiles = Get-ChildItem -Path $ResDir -Recurse -File
foreach ($resource in $ResourceFiles) {
    Invoke-Tool $Aapt2 @("compile", $resource.FullName, "-o", $CompiledDir)
}

$CompiledFiles = @(Get-ChildItem -Path $CompiledDir -Filter *.flat -File | ForEach-Object { $_.FullName })
$LinkArgs = @(
    "link",
    "-o", $UnsignedApk,
    "--manifest", $Manifest,
    "-I", $PlatformJar,
    "--java", $GenDir,
    "--min-sdk-version", "23",
    "--target-sdk-version", "35",
    "--version-code", "1",
    "--version-name", "0.1.0",
    "--auto-add-overlay"
) + $CompiledFiles
Invoke-Tool $Aapt2 $LinkArgs

$JavaFiles = @(
    Get-ChildItem -Path $JavaDir -Recurse -Filter *.java -File
    Get-ChildItem -Path $GenDir -Recurse -Filter *.java -File
) | ForEach-Object { $_.FullName }

$JavacArgsFile = Join-Path $BuildDir "javac-sources.txt"
$JavaFiles | ForEach-Object { '"' + ($_.Replace("\", "/")) + '"' } | Set-Content -LiteralPath $JavacArgsFile -Encoding ASCII
Invoke-Tool $Javac @("-encoding", "UTF-8", "-source", "17", "-target", "17", "-classpath", $Classpath, "-d", $ClassesDir, "@$JavacArgsFile")
Invoke-Tool $JarTool @("--create", "--file", $ClassesJar, "-C", $ClassesDir, ".")
$D8Inputs = @($ClassesJar) + $LibJars
$D8Args = @("--lib", $PlatformJar, "--output", $DexDir) + $D8Inputs
Invoke-Tool $D8 $D8Args

Copy-Item -LiteralPath $UnsignedApk -Destination $UnsignedDexApk -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($UnsignedDexApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $existing = $zip.GetEntry("classes.dex")
    if ($existing) {
        $existing.Delete()
    }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $DexDir "classes.dex"), "classes.dex") | Out-Null
}
finally {
    $zip.Dispose()
}

Invoke-Tool $Zipalign @("-f", "4", $UnsignedDexApk, $AlignedApk)

if (!(Test-Path $Keystore)) {
    Invoke-Tool $Keytool @(
        "-genkeypair",
        "-keystore", $Keystore,
        "-storepass", "android",
        "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Android Debug,O=Android,C=US",
        "-storetype", "PKCS12",
        "-noprompt"
    )
}

Invoke-Tool $Apksigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--v1-signing-enabled", "true",
    "--v2-signing-enabled", "true",
    "--v3-signing-enabled", "true",
    "--out", $SignedApk,
    $AlignedApk
)

Invoke-Tool $Apksigner @("verify", "--verbose", $SignedApk)
Copy-Item -LiteralPath $SignedApk -Destination $OutputApk -Force
Write-Host "APK built: $OutputApk"
