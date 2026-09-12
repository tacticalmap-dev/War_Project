// fix-dsh-raw-html-color-engine.mjs — 补回 /vendor/VCPColorEngine.js
// 依据：lib/client.js L504-507  ensureColorEngine() → loadScriptOnce('/vendor/VCPColorEngine.js')
//       L763-790  applyVcpColorVars(root)  用 engine.generate(opts) 写入整套 --vcp-* 变量
//       L64       路由从 <plugin>/assets/vendor 读取
// 现状：该文件在本机两个插件副本里都缺失（HTTP 404）→ data-vcp-preset 不产生任何
//       --vcp-* 变量（卡片只剩自己写的 hex，视觉仍可用，但声明式配色失效）。
import fs from 'node:fs'
import path from 'node:path'

const URLS = [
  'https://raw.githubusercontent.com/plolpl789/dsh-raw-html/main/assets/vendor/VCPColorEngine.js',
  'https://cdn.jsdelivr.net/gh/plolpl789/dsh-raw-html@main/assets/vendor/VCPColorEngine.js',
]
const DIRS = [
  'C:\\Users\\flowingsun\\.dsh\\profiles\\web-desktop\\node_modules\\dsh-raw-html',
  'C:\\Users\\flowingsun\\AppData\\Local\\Deepseek Harness EAC\\dsh-desktop\\assets\\plugins\\dsh-raw-html',
]
const log = (...a) => process.stderr.write(a.join(' ') + '\n')

let buf = null
for (const u of URLS) {
  try {
    const r = await fetch(u, { redirect: 'follow' })
    if (!r.ok) throw new Error('HTTP ' + r.status)
    const b = Buffer.from(await r.arrayBuffer())
    if (b.length < 5000) throw new Error('too small: ' + b.length)
    if (!/VCPColorEngine|generate/i.test(b.toString('utf8', 0, 2000))) throw new Error('marker missing')
    buf = b
    log('[get]', u, (b.length / 1024).toFixed(1) + ' KB')
    break
  } catch (e) {
    log('[get] FAIL', u, e.message)
  }
}
if (!buf) { log('!! 未取得 VCPColorEngine.js，已中止'); process.exit(1) }

let wrote = 0
for (const dir of DIRS) {
  if (!fs.existsSync(path.join(dir, 'lib', 'client.js'))) { log('[skip]', dir); continue }
  const dst = path.join(dir, 'assets', 'vendor', 'VCPColorEngine.js')
  fs.mkdirSync(path.dirname(dst), { recursive: true })
  try {
    fs.writeFileSync(dst, buf)
    log('[write]', dst)
    wrote++
  } catch (e) {
    log('[write] FAIL', dst, e.message)
  }
}
log(`完成：写入 ${wrote} 个副本`)
