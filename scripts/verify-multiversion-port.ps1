param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$failures = [System.Collections.Generic.List[string]]::new()

function Read-ProjectFile([string]$RelativePath) {
    $path = Join-Path $ProjectRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        $failures.Add("missing file: $RelativePath")
        return $null
    }
    return [System.IO.File]::ReadAllText($path)
}

function Require-Match([string]$RelativePath, [string]$Pattern, [string]$Label) {
    $text = Read-ProjectFile $RelativePath
    if ($null -ne $text -and $text -notmatch $Pattern) {
        $failures.Add("missing anchor: $Label ($RelativePath)")
    }
}

function Forbid-Match([string]$RelativePath, [string]$Pattern, [string]$Label) {
    $text = Read-ProjectFile $RelativePath
    if ($null -ne $text -and $text -match $Pattern) {
        $failures.Add("forbidden anchor: $Label ($RelativePath)")
    }
}

Require-Match 'src/main/java/me/cortex/voxy/common/platform/PlatformUtil.java' 'interface\s+PlatformUtil' 'NeoForge platform interface'
Require-Match 'src/main/java/me/cortex/voxy/common/platform/PlatformUtilImpl.java' 'class\s+PlatformUtilImpl' 'NeoForge platform implementation'
Require-Match 'src/main/java/me/cortex/voxy/commonImpl/VoxyCommon.java' 'getPlatformUtil\s*\(' 'platform utility exposure'
Require-Match 'src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinRenderSystem.java' '@At\("HEAD"\)' 'renderer initialization at HEAD'
Require-Match 'src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinMinecraft.java' '"disconnect"\s*,\s*"clearLevel"' 'disconnect and clearLevel lifecycle hook'
Require-Match 'src/main/java/me/cortex/voxy/client/mixin/sodium/MixinCloudRenderer.java' ',\s*256\s*\)' 'adaptive cloud cap 256'
Require-Match 'src/main/java/me/cortex/voxy/client/mixin/sodium/MixinRenderSectionManager.java' '@Shadow\s*\(\s*aliases\s*=\s*"world"\s*\)' 'Sodium world field alias'
Require-Match 'src/main/java/me/cortex/voxy/common/util/cpu/CpuLayout.java' 'org\.lwjgl\.system\.Platform' 'LWJGL platform detection'
Require-Match 'src/main/java/me/cortex/voxy/client/core/rendering/ViewportSelector.java' 'VRRenderingAPI' 'Vivecraft viewport integration'
Forbid-Match 'src/main/java/me/cortex/voxy/client/config/VoxyConfigRegistration.java' 'if\s*\(\s*!VoxyCommon\.isAvailable\(\)\s*\)\s*return' 'early config-screen registration skip'

Require-Match 'src/main/java/me/cortex/voxy/client/mixin/nvidium/MixinRenderPipeline.java' '@Pseudo' 'optional Nvidium mixin'
Require-Match 'src/main/java/me/cortex/voxy/commonImpl/mixin/chunky/MixinNeoForgeWorld.java' '@Pseudo' 'optional Chunky mixin'
Require-Match 'src/main/resources/client.voxy.mixins.json' 'nvidium\.MixinRenderPipeline' 'Nvidium mixin registration'
Require-Match 'src/main/resources/common.voxy.mixins.json' 'chunky\.MixinNeoForgeWorld' 'Chunky mixin registration'
Require-Match 'src/main/resources/assets/voxy/lang/en_us.json' 'voxy\.config\.general\.adaptCloudDistance' 'cloud configuration translations'
Require-Match 'src/main/resources/assets/voxy/lang/en_us.json' 'voxy\.config\.general\.fogIntensity' 'fog intensity translations'
Require-Match 'src/main/resources/assets/voxy/lang/en_us.json' 'voxy\.config\.general\.skyFogDistance' 'sky fog translations'

Require-Match 'src/main/resources/META-INF/accesstransformer.cfg' 'PalettedContainer\$Data\s+<init>' 'PalettedContainer Data constructor access'
Require-Match 'src/main/resources/META-INF/neoforge.mods.toml' 'versionRange="\[21\.1\.228,\)"' 'NeoForge 21.1.228 floor'
Require-Match 'src/main/resources/META-INF/neoforge.mods.toml' 'versionRange="\[0\.8\.12,\)"' 'Sodium 0.8.12 floor'
Require-Match 'src/main/resources/META-INF/neoforge.mods.toml' 'modId="iris"' 'optional Iris metadata'
Require-Match 'src/main/resources/META-INF/neoforge.mods.toml' 'modId="sable"' 'Sable incompatibility metadata'

Require-Match 'src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java' 'blockColors\.blockColors\.containsKey\(block\)' 'modded block tint provider detection'
Require-Match 'src/main/resources/assets/voxy/shaders/post/blit_texture_depth_cutout.frag' 'float\s+getFragDistance\s*\(' 'local fog distance helper'
Forbid-Match 'src/main/resources/assets/voxy/shaders/post/blit_texture_depth_cutout.frag' '(?m)^\s*#import\s+<sodium:include/fog\.glsl>' 'active Sodium fog include'
Forbid-Match 'src/main/java/me/cortex/voxy/common/config/Serialization.java' 'clazzs\.addAll\(collectAllClasses\(BASE_SEARCH_PACKAGE\)\)' 'classloader package scan'

$modAnnotations = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'src/main/java') -Recurse -Filter '*.java' |
    Select-String -Pattern '@Mod\s*\(' | Measure-Object | Select-Object -ExpandProperty Count
if ($modAnnotations -ne 1) {
    $failures.Add("expected exactly one @Mod annotation, found $modAnnotations")
}

foreach ($native in @('lwjgl_lmdb.dll', 'lwjgl_zstd.dll', 'liblwjgl_lmdb.so', 'liblwjgl_zstd.so')) {
    if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot "src/main/resources/$native"))) {
        $failures.Add("missing bundled native: $native")
    }
}

if ($failures.Count -gt 0) {
    Write-Host "Voxy multiversion port verification failed ($($failures.Count) checks):" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Voxy multiversion port verification passed.' -ForegroundColor Green
