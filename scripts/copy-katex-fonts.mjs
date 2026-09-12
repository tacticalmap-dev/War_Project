// copy-katex-fonts.mjs — 把 KaTeX 的 60 个字体文件补进 vendor/fonts
// 依据：katex-vd.css 里 60 条 url(fonts/KaTeX_*.woff2|woff|ttf)，相对 CSS 自身路径解析
//       → 必须落在 /vendor/fonts/ 下，否则公式字形回退系统字体（能显示但不标准）。
import fs from 'node:fs'
import path from 'node:path'

const SRC = 'C:\\Users\\flowingsun\\AppData\\Local\\Deepseek Harness EAC\\dsh-desktop\\node_modules\\katex\\dist\\fonts'
const DIRS = [
  'C:\\Users\\flowingsun\\.dsh\\profiles\\web-desktop\\node_modules\\dsh-raw-html',
  'C:\\Users\\flowingsun\\AppData\\Local\\Deepseek Harness EAC\\dsh-desktop\\assets\\plugins\\dsh-raw-html',
]
const log = (...a) => process.stderr.write(a.join(' ') + '\n')
const kb = (n) => (n / 1024).toFixed(1) + ' KB'

if (!fs.existsSync(SRC)) { log('源目录不存在:', SRC); process.exit(1) }
const files = fs.readdirSync(SRC)
let total = 0
const bufs = new Map()
for (const f of files) {
  const b = fs.readFileSync(path.join(SRC, f))
  bufs.set(f, b)
  total += b.length
}
log(`源字体 ${files.length} 个，共 ${kb(total)}`)

for (const dir of DIRS) {
  if (!fs.existsSync(path.join(dir, 'lib', 'client.js'))) { log('[skip]', dir); continue }
  const dst = path.join(dir, 'assets', 'vendor', 'fonts')
  fs.mkdirSync(dst, { recursive: true })
  for (const [f, b] of bufs) fs.writeFileSync(path.join(dst, f), b)
  log('[write]', dst, files.length, '个文件', kb(total))
}
