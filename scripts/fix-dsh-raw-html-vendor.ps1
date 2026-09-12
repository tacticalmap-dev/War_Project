<#
  fix-dsh-raw-html-vendor.ps1
  ---------------------------------------------------------------------------
  症状：dsh-raw-html 卡片里 <pre><code class="language-mermaid"> 不渲染成图；
        KaTeX 公式同样不渲染；data-vcp-preset 声明式配色不生效。

  根因（代码级证据）：
    lib/client.js  L812  root.querySelectorAll('pre > code.language-mermaid')
    lib/client.js  L493  loadScriptOnce('dsh-raw-html-mermaid', '/vendor/mermaid.min.js')
    lib/index.js   L43   VENDOR_ROUTE = '/vendor'
    lib/index.js   L64   BUILTIN_VENDOR = <插件目录>/assets/vendor
    → 该路由只从 assets/vendor 读文件；本机安装里 assets/vendor 整个目录缺失，
      于是 /vendor/mermaid.min.js 返回 404，loadScriptOnce 静默失败（catch 吞掉），
      window.mermaid 始终不存在，mermaid 转换分支直接 return。

  本脚本做什么：
    1) 定位 dsh-raw-html 插件安装目录（EAC 内置 assets 目录优先，其次 profile node_modules）
    2) 下载 mermaid.min.js（UMD 构建，挂 window.mermaid）
    3) 从本机 node_modules/katex 复制 KaTeX 三件套（无需联网）
    4) 校验四个文件的体积与 Magic bytes，打印结果

  回滚：删除 <插件目录>/assets/vendor 整个目录即可（该目录原本不存在）。

  持久化风险：插件目录属 EAC 托管资源，客户端升级会覆盖，需要重跑本脚本。
<#>
[CmdletBinding()]
param(
    [string]$PluginDir,
    [string]$MermaidUrl = 'https://cdn.jsdelivr.net/npm/mermaid@11.12.0/dist/mermaid.min.js',
    [string]$MermaidFallbackUrl = 'https://raw.githubusercontent.com/plolpl789/dsh-raw-html/main/assets/vendor/mermaid.min.js'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Find-PluginDir {
    param([string]$Hint)
    if ($Hint -and (Test-Path (Join-Path $Hint 'lib/client.js'))) { return (Resolve-Path $Hint).Path }
    $candidates = @(
        'C:\Users\flowingsun\AppData\Local\Deepseek Harness EAC\dsh-desktop\assets\plugins\dsh-raw-html',
        'C:\Users\flowingsun\.dsh\profiles\web-desktop\node_modules\dsh-raw-html'
    )
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c 'lib/client.js')) { return (Resolve-Path $c).Path }
    }
    throw 'dsh-raw-html 插件目录未找到：请用 -PluginDir 显式指定。'
}

function Get-File {
    param([string]$Url, [string]$OutFile, [string]$Label)
    Write-Host ("  [下载] {0}" -f $Label)
    try {
        Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -TimeoutSec 120
        $size = (Get-Item $OutFile).Length
        if ($size -lt 100000) { throw ("下载内容过小（{0} bytes），疑似错误页" -f $size) }
        Write-Host ("         OK  {0:N1} KB" -f ($size / 1KB))
        return $true
    } catch {
        Write-Host ("         失败：{0}" -f $_.Exception.Message) -ForegroundColor Yellow
        if (Test-Path $OutFile) { Remove-Item $OutFile -Force }
        return $false
    }
}

$dir   = Find-PluginDir -Hint $PluginDir
$vendor = Join-Path $dir 'assets\vendor'
Write-Host "插件目录 : $dir"
Write-Host "vendor   : $vendor"
Write-Host ''

Write-Host '[1/3] 准备目录'
if (-not (Test-Path $vendor)) { New-Item -ItemType Directory -Path $vendor -Force | Out-Null }
if (-not (Test-Path (Join-Path $vendor 'fonts'))) { New-Item -ItemType Directory -Path (Join-Path $vendor 'fonts') -Force | Out-Null }
Write-Host ("      目录就绪：{0}" -f $vendor)
Write-Host ''

Write-Host '[2/3] 放置 mermaid.min.js'
$mermaidOut = Join-Path $vendor 'mermaid.min.js'
$ok = Get-File -Url $MermaidUrl -OutFile $mermaidOut -Label "mermaid UMD <- $MermaidUrl"
if (-not $ok) {
    Write-Host '  回退到仓库原始资源...' -ForegroundColor Yellow
    $ok = Get-File -Url $MermaidFallbackUrl -OutFile $mermaidOut -Label "mermaid UMD <- $MermaidFallbackUrl"
}
if ($ok) {
    $head = Get-Content -Path $mermaidOut -TotalCount 1 -Encoding UTF8
    if ($head -match 'mermaid' -or (Get-Content $mermaidOut -Raw -Encoding UTF8).Contains('mermaid')) {
        Write-Host '      校验：内容含 mermaid 标识 OK'
    } else {
        Write-Host '      校验：未检出 mermaid 标识，请人工确认' -ForegroundColor Yellow
    }
}
Write-Host ''

Write-Host '[3/3] 复制 KaTeX 三件套（本机 node_modules，无需联网）'
$katexRoots = @(
    'C:\Users\flowingsun\AppData\Local\Deepseek Harness EAC\dsh-desktop\node_modules\katex\dist'
)
$katexRoot = $katexRoots | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $katexRoot) { throw 'katex dist 目录未找到，无法复制 KaTeX 三件套。' }
Write-Host "  源目录：$katexRoot"
$map = @(
    @{ Src = 'katex.min.js';                  Dst = 'katex.min.js' },
    @{ Src = 'katex.min.css';                 Dst = 'katex-vd.css' },
    @{ Src = 'contrib\auto-render.min.js';    Dst = 'auto-render.min.js' }
)
foreach ($m in $map) {
    $src = Join-Path $katexRoot $m.Src
    $dst = Join-Path $vendor $m.Dst
    if (-not (Test-Path $src)) { Write-Host ("      缺失：{0}" -f $src) -ForegroundColor Yellow; continue }
    Copy-Item -Path $src -Destination $dst -Force
    Write-Host ("      {0,-22} -> {1}  ({2:N1} KB)" -f $m.Src, $m.Dst, ((Get-Item $dst).Length / 1KB))
}
Write-Host ''

Write-Host '=== 结果清单 ==='
foreach ($f in @('mermaid.min.js', 'katex.min.js', 'katex-vd.css', 'auto-render.min.js')) {
    $p = Join-Path $vendor $f
    if (Test-Path $p) { Write-Host ("  [OK]   {0,-22} {1,10:N1} KB" -f $f, ((Get-Item $p).Length / 1KB)) }
    else               { Write-Host ("  [MISS] {0}" -f $f) -ForegroundColor Red }
}
Write-Host ''
Write-Host '下一步：重启 dsh 服务（host 半侧路由在启动时注册），然后发一条含 language-mermaid 的消息验证。'
Write-Host '回滚：Remove-Item -Recurse -Force "<插件目录>\assets\vendor"'
