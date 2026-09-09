// 构建后把 docs/**/*.md 镜像到 .vitepress/dist/md/，并在站点根生成 llms.txt。
// 镜像内指向本站文档页的 Markdown 链接改写为 .md 直链（绝对 URL），让 AI 顺着抓到的都是纯 md。
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, posix } from 'node:path'
import { fileURLToPath } from 'node:url'

const SITE_URL = 'https://aicode.murk.top'
const here = dirname(fileURLToPath(import.meta.url))
const docsDir = join(here, '..', 'docs')
const distDir = join(here, '..', '.vitepress', 'dist')
const mdOutDir = join(distDir, 'md')

/** 递归收集 docs 下所有 .md 的相对路径（posix 分隔）。 */
function collectMd(dir, base) {
  const out = []
  for (const name of readdirSync(dir)) {
    if (name === 'public') continue
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      out.push(...collectMd(full, base))
    } else if (name.endsWith('.md')) {
      out.push(posix.relative(base, full))
    }
  }
  return out
}

// 站内 HTML 页路径（无 .md 无锚点）→ md 相对路径。仅收录真实存在的页面，其余链接原样保留。
const rels = collectMd(docsDir, docsDir).sort()
const pageToMd = new Map()
for (const rel of rels) {
  pageToMd.set('/' + rel.replace(/\.md$/, ''), rel)
}
pageToMd.set('/', 'index.md')

/** 行级处理：跳过围栏代码块，仅改写 Markdown 链接语法的站内文档页链接。 */
function rewriteLinks(text) {
  let inCode = false
  return text
    .split('\n')
    .map((line) => {
      if (/^\s*```/.test(line)) {
        inCode = !inCode
        return line
      }
      if (inCode) return line
      return line.replace(/\]\(([^)]+)\)/g, (whole, target) => {
        const hashAt = target.indexOf('#')
        const path = hashAt === -1 ? target : target.slice(0, hashAt)
        const frag = hashAt === -1 ? '' : target.slice(hashAt)
        const mapped = pageToMd.get(path)
        return mapped ? `](${SITE_URL}/md/${mapped}${frag})` : whole
      })
    })
    .join('\n')
}

/** 提取 { title, description }：title 取 frontmatter 或首个 H1；description 取 frontmatter 或 H1 后首个正文段。 */
function extractMeta(text, fallbackTitle) {
  const lines = text.split('\n')
  let title = ''
  let desc = ''
  let i = 0
  if (lines[0].trim() === '---') {
    for (i = 1; i < lines.length; i++) {
      const line = lines[i].trim()
      if (line === '---') {
        i++
        break
      }
      const titleMatch = line.match(/^title:\s*(.+)$/)
      const descMatch = line.match(/^description:\s*(.+)$/)
      if (!title && titleMatch) title = titleMatch[1].trim().replace(/^['"]|['"]$/g, '')
      if (!desc && descMatch) desc = descMatch[1].trim().replace(/^['"]|['"]$/g, '')
    }
  }
  for (; i < lines.length; i++) {
    const line = lines[i]
    if (!title) {
      const heading = line.match(/^#\s+(.+)$/)
      if (heading) {
        title = heading[1].trim()
        continue
      }
    }
    const trimmed = line.trim()
    if (
      !desc &&
      trimmed &&
      !trimmed.startsWith('#') &&
      !trimmed.startsWith(':::') &&
      !/^\s*[-*>\d.`]/.test(trimmed)
    ) {
      desc = trimmed.length > 120 ? trimmed.slice(0, 120).trimEnd() + '…' : trimmed
      break
    }
  }
  return { title: title || fallbackTitle, description: desc }
}

mkdirSync(mdOutDir, { recursive: true })

// 首页单列「入口」，guide/advanced 各自成节。
const sections = [
  { name: '入口', rels: ['index.md'] },
  { name: '使用手册（guide）', rels: rels.filter((r) => r.startsWith('guide/')) },
  { name: '进阶教程（advanced）', rels: rels.filter((r) => r.startsWith('advanced/')) },
  { name: 'English', rels: rels.filter((r) => r.startsWith('en/')) }
]

const metaByRel = new Map()
for (const section of sections) {
  for (const rel of section.rels) {
    const raw = readFileSync(join(docsDir, rel), 'utf8')
    const rewritten = rewriteLinks(raw)
    const target = rel === 'index.md' ? 'index.md' : rel
    const outPath = join(mdOutDir, target)
    mkdirSync(dirname(outPath), { recursive: true })
    writeFileSync(outPath, rewritten)
    const meta = extractMeta(raw, target)
    if (rel === 'index.md') meta.title = '文档站首页'
    metaByRel.set(rel, meta)
  }
}

const lines = [
  '# AiCode 文档',
  '',
  `> AiCode 用户文档的纯 Markdown 镜像，正文即 .md 原文，可直接整页抓取。HTML 版见 ${SITE_URL}。`,
  ''
]
for (const section of sections) {
  lines.push(`## ${section.name}`, '')
  for (const rel of section.rels) {
    const { title, description } = metaByRel.get(rel)
    const url = rel === 'index.md' ? `${SITE_URL}/md/index.md` : `${SITE_URL}/md/${rel}`
    lines.push(`- [${title}](${url})${description ? `: ${description}` : ''}`)
  }
  lines.push('')
}
writeFileSync(join(distDir, 'llms.txt'), lines.join('\n'))
console.log(`export-ai-docs: ${rels.length} markdown 镜像到 ${mdOutDir}，llms.txt 已生成`)
