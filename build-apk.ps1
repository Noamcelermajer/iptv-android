$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidDir  = Join-Path $ProjectRoot "android"
$BuildDir    = Join-Path $ProjectRoot "build"
$DistDir     = Join-Path $ProjectRoot "dist"

$JdkBin      = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\bin"
$SdkRoot     = "C:\Android\Sdk"
$BuildTools  = Join-Path $SdkRoot "build-tools\37.0.0"
$PlatformDir = Join-Path $SdkRoot "platforms\android-36"

$Aapt2       = Join-Path $BuildTools "aapt2.exe"
$D8          = Join-Path $BuildTools "d8.bat"
$ZipAlign    = Join-Path $BuildTools "zipalign.exe"
$ApkSigner   = Join-Path $BuildTools "apksigner.bat"
$CoreLambda  = Join-Path $BuildTools "core-lambda-stubs.jar"
$AndroidJar  = Join-Path $PlatformDir "android.jar"

$Javac       = Join-Path $JdkBin "javac.exe"
$Jar         = Join-Path $JdkBin "jar.exe"
$Keytool     = Join-Path $JdkBin "keytool.exe"

foreach ($tool in @($Aapt2, $D8, $ZipAlign, $ApkSigner, $CoreLambda, $AndroidJar, $Javac, $Jar, $Keytool)) {
    if (-not (Test-Path $tool)) {
        throw "Required build tool or SDK file missing: $tool"
    }
}

Write-Host "=== IPTV Android TV APK Builder ===" -ForegroundColor Cyan

# Generate the launcher icon (192x192) and Leanback banner (320x180)
Write-Host "[2/8] Generating Android TV launcher icon & Leanback banner..."
$DrawableDir = Join-Path $AndroidDir "res\drawable"
New-Item -ItemType Directory -Force -Path $DrawableDir | Out-Null

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function New-GradientBrush {
    param([int]$W, [int]$H, [System.Drawing.Color]$C1, [System.Drawing.Color]$C2)
    $rect = New-Object System.Drawing.Rectangle(0, 0, $W, $H)
    return New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $C1, $C2, 90.0)
}

function Add-RoundedRectPath {
    param([System.Drawing.Drawing2D.GraphicsPath]$Path, [float]$X, [float]$Y, [float]$W, [float]$H, [float]$R)
    $d = $R * 2
    $Path.AddArc($X, $Y, $d, $d, 180, 90)
    $Path.AddArc($X + $W - $d, $Y, $d, $d, 270, 90)
    $Path.AddArc($X + $W - $d, $Y + $H - $d, $d, $d, 0, 90)
    $Path.AddArc($X, $Y + $H - $d, $d, $d, 90, 90)
    $Path.CloseFigure()
}

# 192x192 App Icon (graphite and white)
$iconBmp = New-Object System.Drawing.Bitmap(192, 192)
$g = [System.Drawing.Graphics]::FromImage($iconBmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

$bgBrush = New-GradientBrush 192 192 ([System.Drawing.Color]::FromArgb(255, 28, 28, 34)) ([System.Drawing.Color]::FromArgb(255, 12, 12, 15))
$g.FillRectangle($bgBrush, 0, 0, 192, 192)

$innerPath = New-Object System.Drawing.Drawing2D.GraphicsPath
Add-RoundedRectPath $innerPath 28 28 136 136 32
$innerBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 24, 24, 29))
$g.FillPath($innerBrush, $innerPath)

$playPts = [System.Drawing.PointF[]]@(
    (New-Object System.Drawing.PointF(76, 62)),
    (New-Object System.Drawing.PointF(126, 96)),
    (New-Object System.Drawing.PointF(76, 130))
)
$whiteBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 245, 245, 247))
$g.FillPolygon($whiteBrush, $playPts)

$iconBmp.Save((Join-Path $DrawableDir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $iconBmp.Dispose()

# 320x180 Android TV Leanback Banner (graphite)
$bannerBmp = New-Object System.Drawing.Bitmap(320, 180)
$gb = [System.Drawing.Graphics]::FromImage($bannerBmp)
$gb.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$gb.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

$bbg = New-GradientBrush 320 180 ([System.Drawing.Color]::FromArgb(255, 26, 26, 32)) ([System.Drawing.Color]::FromArgb(255, 11, 11, 14))
$gb.FillRectangle($bbg, 0, 0, 320, 180)

$emPath = New-Object System.Drawing.Drawing2D.GraphicsPath
Add-RoundedRectPath $emPath 38 56 68 68 18
$emBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 245, 245, 247))
$gb.FillPath($emBrush, $emPath)

$triPts = [System.Drawing.PointF[]]@(
    (New-Object System.Drawing.PointF(63, 74)),
    (New-Object System.Drawing.PointF(88, 90)),
    (New-Object System.Drawing.PointF(63, 106))
)
$darkBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 12, 12, 15))
$gb.FillPolygon($darkBrush, $triPts)

$titleFont = New-Object System.Drawing.Font("Segoe UI", 24, [System.Drawing.FontStyle]::Bold)
$subFont   = New-Object System.Drawing.Font("Segoe UI", 10, [System.Drawing.FontStyle]::Regular)
$grayBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 152, 152, 159))

$gb.DrawString("IPTV", $titleFont, $emBrush, 120, 62)
$gb.DrawString("Live TV, Movies & Series", $subFont, $grayBrush, 123, 104)

$bannerBmp.Save((Join-Path $DrawableDir "tv_banner.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$gb.Dispose(); $bannerBmp.Dispose()

# Prepare clean build directories (keep keystore if already generated)
Write-Host "[3/8] Preparing build workspace..."
foreach ($sub in @("compiled-res", "gen", "classes", "dex")) {
    $p = Join-Path $BuildDir $sub
    if (Test-Path $p) { Remove-Item -Recurse -Force $p }
    New-Item -ItemType Directory -Force -Path $p | Out-Null
}
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

# Compile resources
Write-Host "[4/8] Compiling Android resources with aapt2..."
$ResZip = Join-Path $BuildDir "compiled-res\res.zip"
& $Aapt2 compile --dir (Join-Path $AndroidDir "res") -o $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed with exit code $LASTEXITCODE" }

# Link manifest + resources + assets
Write-Host "[5/8] Linking resources, manifest, and assets..."
$UnalignedApk = Join-Path $BuildDir "unsigned-unaligned.apk"
if (Test-Path $UnalignedApk) { Remove-Item -Force $UnalignedApk }

& $Aapt2 link `
    -o $UnalignedApk `
    --manifest (Join-Path $AndroidDir "AndroidManifest.xml") `
    -I $AndroidJar `
    --java (Join-Path $BuildDir "gen") `
    --min-sdk-version 26 `
    --target-sdk-version 35 `
    --version-code 2 `
    --version-name "2.0.0" `
    --auto-add-overlay `
    $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed with exit code $LASTEXITCODE" }

# Compile Java sources
Write-Host "[6/8] Compiling Java sources with javac (JDK 17 -> Java 8 target)..."
$JavaFiles = @()
$JavaFiles += Get-ChildItem -Path (Join-Path $BuildDir "gen") -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
$JavaFiles += Get-ChildItem -Path (Join-Path $AndroidDir "src") -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName

$ClassesDir = Join-Path $BuildDir "classes"
$BootClasspath = "$AndroidJar;$CoreLambda"
& $Javac -source 8 -target 8 -bootclasspath $BootClasspath -Xlint:-options -encoding UTF-8 -d $ClassesDir @JavaFiles
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed with exit code $LASTEXITCODE" }

$ClassesJar = Join-Path $BuildDir "classes.jar"
& $Jar cf $ClassesJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { throw "jar packaging failed with exit code $LASTEXITCODE" }

# Convert bytecode to DEX
Write-Host "[7/8] Converting JVM classes to Dalvik DEX with d8..."
$DexDir = Join-Path $BuildDir "dex"
& $D8 --min-api 26 --lib $AndroidJar --output $DexDir $ClassesJar
if ($LASTEXITCODE -ne 0) { throw "d8 dexing failed with exit code $LASTEXITCODE" }

$ClassesDex = Join-Path $DexDir "classes.dex"
if (-not (Test-Path $ClassesDex)) { throw "classes.dex was not generated at $ClassesDex" }

# Inject classes.dex directly into the APK zip without recompressing resources.arsc
$zip = [System.IO.Compression.ZipFile]::Open($UnalignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $existing = $zip.GetEntry("classes.dex")
    if ($existing) { $existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $ClassesDex, "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

# Align and Sign
Write-Host "[8/8] Aligning and signing APK..."
$AlignedApk = Join-Path $BuildDir "unsigned-aligned.apk"
& $ZipAlign -f -p 4 $UnalignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE" }

$KeystorePath = Join-Path $BuildDir "iptv-release.jks"
if (-not (Test-Path $KeystorePath)) {
    & $Keytool -genkeypair -v `
        -keystore $KeystorePath `
        -alias iptv `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -storepass iptv-local-key `
        -keypass iptv-local-key `
        -dname "CN=IPTV, OU=TV, O=IPTV, L=Local, ST=State, C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool keystore generation failed with exit code $LASTEXITCODE" }
}

$FinalApk = Join-Path $DistDir "iptv.apk"
& $ApkSigner sign `
    --ks $KeystorePath `
    --ks-key-alias iptv `
    --ks-pass pass:iptv-local-key `
    --key-pass pass:iptv-local-key `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --out $FinalApk `
    $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed with exit code $LASTEXITCODE" }

Write-Host ""
Write-Host "Verifying APK signature and Android TV Leanback manifest..." -ForegroundColor Green
& $ApkSigner verify --verbose $FinalApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed with exit code $LASTEXITCODE" }

$apkSizeMb = [math]::Round((Get-Item $FinalApk).Length / 1MB, 2)
Write-Host ""
Write-Host "SUCCESS! Built signed Android TV APK: $FinalApk ($apkSizeMb MB)" -ForegroundColor Green
