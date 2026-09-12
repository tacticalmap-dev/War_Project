// fix-dsh-raw-html-vendor.mjs — 补回 dsh-raw-html 缺失的 assets/vendor 载荷
// 用法： node fix-dsh-raw-html-vendor.mjs
// 依据（代码级证据）：
//   lib/client.js L812  root.querySelectorAll('pre > code.language-mermaid')
//   lib/client.js L493  loadScriptOnce('dsh-raw-html-mermaid', '/vendor/mermaid.min.js')
//   lib/index.js  L43   VENDOR_ROUTE = '/vendor'
//   lib/index.js  L64   BUILTIN_VENDOR = <plugin>/assets/vendor   ← 本机整个目录缺失
import fs from 'node:fs'
import path from 'node:path'

// 两个副本都要写：
//   · EAC 内置 assets 副本 = 分发源（升级时被覆盖，改了会被同步覆盖）
//   · profile node_modules 副本 = 运行中 host 真正读取的位置
//     （lib/index.js L64 的 PLUGIN_DIR 指向本副本；实测该副本缺 assets/vendor 时
//      /vendor/* 一律 404，而 /fonts/* 200 —— 与"host 读 profile 副本"一致）
const PLUGIN_DIRS = [
  'C:\\Users\\flowingsun\\.dsh\\profiles\\web-desktop\\node_modules\\dsh-raw-html',
  'C:\\Users\\flowingsun\\AppData\\Local\\Deepseek Harness EAC\\dsh-desktop\\assets\\plugins\\dsh-raw-html',
]
const KATEX_SRC = 'C:\\Users\\flowingsun\\AppData\\Local\\Deepseek Harness EAC\\dsh-desktop\\node_modules\\katex\\dist'

const MERMAID_URLS = [
  'https://cdn.jsdelivr.net/npm/mermaid@11.12.0/dist/mermaid.min.js',
  'https://unpkg.com/mermaid@11.12.0/dist/mermaid.min.js',
]
const KATEX_FILES = [
  ['katex.min.js', 'katex.min.js'],
  ['katex.min.css', 'katex-vd.css'],
  [path.join('contrib', 'auto-render.min.js'), 'auto-render.min.js'],
]

const log = (...a) => process.stderr.write(a.join(' ') + '\n')
const kb = (n) => (n / 1024).toFixed(1) + ' KB'

// 0) 下载/准备素材到内存（只取一次，再分发到各副本）
const payload = new Map()

let ok = false
for (const url of MERMAID_URLS) {
  try {
    log('[get]', url)
    const res = await fetch(url, { redirect: 'follow' })
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const buf = Buffer.from(await res.arrayBuffer())
    if (buf.length < 100000) throw new Error('too small: ' + buf.length)
    const head = buf.subarray(0, 400).toString('utf8')
    if (!/mermaid/i.test(head)) throw new Error('no mermaid marker in head')
    payload.set('mermaid.min.js', buf)
    log('      OK', kb(buf.length))
    ok = true
    break
  } catch (e) {
    log('      FAIL', e.message)
  }
}
if (!ok) log('!! mermaid 未获取到，继续处理其余文件')

for (const [src, dst] of KATEX_FILES) {
  const s = path.join(KATEX_SRC, src)
  try {
    payload.set(dst, fs.readFileSync(s))
    log('[read]', src, '->', dst, kb(payload.get(dst).length))
  } catch (e) {
    log('[read] FAIL', src, e.message)
  }
}

// 1) 分发到每个存在的插件副本
for (const dir of PLUGIN_DIRS) {
  if (!fs.existsSync(path.join(dir, 'lib', 'client.js'))) {
    log('[skip] 非插件目录:', dir)
    continue
  }
  const vendor = path.join(dir, 'assets', 'vendor')
  fs.mkdirSync(path.join(vendor, 'fonts'), { recursive: true })
  log('[write]', vendor)
  for (const [name, buf] of payload) {
    fs.writeFileSync(path.join(vendor, name), buf)
    log('        -', name, kb(buf.length))
  }
}

// 2) 结果清单
log('=== vendor 清单 ===')
for (const dir of PLUGIN_DIRS) {
  const vendor = path.join(dir, 'assets', 'vendor')
  log('-- ' + vendor)
  for (const f of ['mermaid.min.js', 'katex.min.js', 'katex-vd.css', 'auto-render.min.js']) {
    const p = path.join(vendor, f)
    let line
    try {
      line = `  [OK]   ${f.padEnd(20)} ${kb(fs.statSync(p).size)}`
    } catch {
      line = `  [MISS] ${f}`
    }
    log(line)
  }
}
