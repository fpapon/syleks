import { readFile, readdir, stat } from 'node:fs/promises'
import { resolve, dirname, relative, join } from 'node:path'
import assert from 'node:assert/strict'
import MarkdownIt from 'markdown-it'

const root = resolve('docs')
const md = new MarkdownIt({ html: true })
const errors = []
const pages = []
async function walk(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.') || ['audits', 'engineering', 'public'].includes(entry.name) && dir === root) continue
    const path = join(dir, entry.name)
    if (entry.isDirectory()) await walk(path)
    else if (entry.name.endsWith('.md')) pages.push(path)
  }
}
await walk(root)
const exists = async path => { try { return (await stat(path)).isFile() } catch { return false } }
async function checkLink(from, href) {
  if (!href) return
  // Source links for this repository can be checked without network access.
  const repository = href.match(/^https:\/\/github\.com\/fpapon\/syleks\/(?:blob|tree)\/main\/([^#?]+)/)
  if (repository) {
    const path = resolve(decodeURIComponent(repository[1]))
    try { await stat(path) } catch { errors.push(`${relative(root, from)}: missing repository file: ${href}`) }
    return
  }
  if (/^(?:[a-z][a-z\d+.-]*:|\/\/|#)/i.test(href)) return
  const path = decodeURIComponent(href.split(/[?#]/)[0])
  if (!path) return
  const target = path.startsWith('/') ? resolve(root, '.' + path) : resolve(dirname(from), path)
  const candidates = [target, target + '.md', join(target, 'index.md'), resolve(root, 'public', '.' + (path.startsWith('/') ? path : '/' + path))]
  if (!target.startsWith(root + '/')) errors.push(`${relative(root, from)}: escapes docs root: ${href}`)
  else if (!(await Promise.all(candidates.map(exists))).some(Boolean)) errors.push(`${relative(root, from)}: missing ${href}`)
  if (/\/(audits|engineering)\//.test(target + '/')) errors.push(`${relative(root, from)}: link to excluded page: ${href}; use GitHub`)
}
for (const page of pages) {
  const source = await readFile(page, 'utf8')
  const body = source.replace(/^---\n[\s\S]*?\n---\n/, '')
  const tokens = md.parse(body, {})
  if (!tokens.some(t => t.type === 'heading_open' && t.tag === 'h1') && !/^title: .+/m.test(source)) errors.push(`${relative(root, page)}: missing title`)
  if (/(?:^|\n)\s*(?:TODO:|FIXME:|TBD\s*$|Lorem ipsum)/m.test(body.replace(/```[\s\S]*?```/g, ''))) errors.push(`${relative(root, page)}: unfinished prose`)
  for (const token of tokens) {
    for (const child of token.children || []) {
      if (child.type === 'link_open') await checkLink(page, child.attrGet('href'))
      if (child.type === 'image') {
        if (!child.content.trim()) errors.push(`${relative(root, page)}: image missing alt text`)
        await checkLink(page, child.attrGet('src'))
      }
    }
    if (token.type === 'html_block') for (const match of token.content.matchAll(/(?:href|src)="([^"]+)"/g)) await checkLink(page, match[1])
  }
}
const navigation = await readFile('docs/.vitepress/navigation.ts', 'utf8')
for (const match of navigation.matchAll(/link: '([^']+)'/g)) await checkLink(resolve(root, 'index.md'), match[1])
const config = await readFile('docs/.vitepress/config.ts', 'utf8')
assert.match(config, /provider: 'local'/, 'Local search must remain enabled')
for (const file of ['index.md', 'installation.md', 'quickstart.md', 'getting-started.md', 'public/favicon.svg']) assert.ok(await exists(resolve(root, file)), `Missing ${file}`)
assert.match(navigation, /Getting Started/)
if (errors.length) throw new Error(errors.join('\n'))
console.log(`Documentation source checks passed: ${pages.length} pages; navigation, titles, links and assets.`)
