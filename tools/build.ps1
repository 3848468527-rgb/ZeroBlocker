# 零号拦截 构建脚本（不依赖 Gradle）
#
# 依赖：
#   - JDK 17
#   - Android SDK：platforms/android-35 + build-tools（含 aapt2 / d8 / zipalign / apksigner）
#
# 用法（PowerShell）：
#   $env:JAVA_HOME    = "C:\Program Files\Java\jdk-17"
#   $env:ANDROID_HOME = "C:\Android\Sdk"
#   powershell -ExecutionPolicy Bypass -File .\tools\build.ps1
#
# 可选参数：
#   -Jdk / -Sdk / -Keystore / -VersionCode / -VersionName
param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Keystore = "",
    [string]$KeystorePass = "android",
    [string]$KeyAlias = "zeroblocker",
    [int]$VersionCode = 6,
    [string]$VersionName = "4.0"
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$app = Join-Path $root 'app'
$build = Join-Path $root 'build'
$releaseDir = Join-Path $root 'release'
$androidJar = Join-Path $Sdk 'platforms\android-35\android.jar'
$btDir = Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory | Select-Object -First 1
$bt = $btDir.FullName

if (-not (Test-Path $androidJar)) { throw "找不到 $androidJar，请检查 ANDROID_HOME 与 platforms/android-35" }
if (-not $bt) { throw "找不到 build-tools 目录" }

$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$bt;$env:PATH"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build, "$build\classes", "$build\dex", $releaseDir | Out-Null

Write-Host "== 1/7 aapt2 compile =="
& "$bt\aapt2.exe" compile --dir "$app\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Host "== 2/7 aapt2 link =="
& "$bt\aapt2.exe" link -o "$build\base.apk" -I $androidJar --manifest "$app\AndroidManifest.xml" `
    -R "$build\res.zip" -A "$app\assets" --auto-add-overlay `
    --min-sdk-version 29 --target-sdk-version 35 `
    --version-code $VersionCode --version-name $VersionName
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "== 3/7 javac =="
$srcs = Get-ChildItem "$app\src" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
& "$Jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d "$build\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "== 4/7 jar + d8 =="
& "$Jdk\bin\jar.exe" cf "$build\classes.jar" -C "$build\classes" .
& "$bt\d8.bat" --lib $androidJar --min-api 29 --output "$build\dex" "$build\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Host "== 5/7 打包 classes.dex =="
Copy-Item "$build\base.apk" "$build\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$build\unsigned.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build\dex\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()

Write-Host "== 6/7 zipalign =="
& "$bt\zipalign.exe" -f -p 4 "$build\unsigned.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "== 7/7 签名 =="
if (-not $Keystore) {
    $Keystore = Join-Path $build 'debug.keystore'
    if (-not (Test-Path $Keystore)) {
        & "$Jdk\bin\keytool.exe" -genkeypair -v -keystore $Keystore -storepass $KeystorePass `
            -keypass $KeystorePass -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=ZeroBlocker,OU=dev,O=ZeroBlocker,C=CN"
    }
}
$apkOut = Join-Path $releaseDir ("ZeroBlocker-" + $VersionName + ".apk")
& "$bt\apksigner.bat" sign --ks $Keystore --ks-pass "pass:$KeystorePass" `
    --key-pass "pass:$KeystorePass" --out $apkOut "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "签名失败" }
& "$bt\apksigner.bat" verify $apkOut

$apk = Get-Item $apkOut
Write-Host ("APK 输出: " + $apk.FullName + "  " + [math]::Round($apk.Length / 1KB, 1) + " KB")
