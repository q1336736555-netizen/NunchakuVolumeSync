param([string]$Sdk = $env:ANDROID_HOME, [string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $Jdk
$bt = Join-Path $Sdk 'build-tools\36.0.0'
$api = Join-Path $Sdk 'platforms\android-37.0\android.jar'
$out = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $out 'classes'
New-Item -Type Directory -Force $classes | Out-Null
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed: $LASTEXITCODE" }
}
Push-Location $PSScriptRoot
try {
    Run "$bt\aapt2.exe" @('compile','--dir','res','-o',"$out\resources.zip")
    Run "$bt\aapt2.exe" @('link','-I',$api,'--manifest','AndroidManifest.xml','-o',"$out\base.apk","$out\resources.zip")
    Run "$Jdk\bin\javac.exe" (@('--release','8','-encoding','UTF-8','-classpath',$api,'-d',$classes) + (Get-ChildItem src\*.java).FullName)
    Run "$bt\d8.bat" (@('--min-api','34','--lib',$api,'--output',$out) + (Get-ChildItem $classes -Recurse -Filter '*.class').FullName)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
    try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$out\classes.dex",'classes.dex') | Out-Null } finally { $zip.Dispose() }
    Run "$bt\zipalign.exe" @('-f','4',"$out\base.apk","$out\aligned.apk")
    $key = Join-Path $env:USERPROFILE '.android\debug.keystore'
    if (!(Test-Path -LiteralPath $key)) {
        New-Item -ItemType Directory -Force (Split-Path -Parent $key) | Out-Null
        Run "$Jdk\bin\keytool.exe" @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Android Debug,O=Android,C=US')
    }
    Run "$bt\apksigner.bat" @('sign','--ks',$key,'--ks-pass','pass:android','--out',"$out\CloudUsbTile.apk","$out\aligned.apk")
    Run "$bt\apksigner.bat" @('verify',"$out\CloudUsbTile.apk")
} finally { Pop-Location }
