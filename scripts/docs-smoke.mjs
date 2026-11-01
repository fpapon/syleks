import { createServer } from 'node:http'
import { readFile, readdir, stat } from 'node:fs/promises'
import { resolve, join, extname } from 'node:path'
import assert from 'node:assert/strict'

const root = resolve('docs/.vitepress/dist')
const base = '/' + (process.env.DOCS_BASE || '/').split('/').filter(Boolean).join('/')
const prefix = base === '/' ? '/' : base + '/'
const files = []
async function walk(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) await walk(path)
    else files.push(path)
  }
}
await walk(root)
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost')
    if (!url.pathname.startsWith(prefix)) throw new Error('Wrong base')
    let path = resolve(root, decodeURIComponent(url.pathname.slice(prefix.length)) || 'index.html')
    if (!path.startsWith(root + '/')) throw new Error('Invalid path')
    if (!extname(path)) path += '.html'
    if (!(await stat(path)).isFile()) throw new Error('Missing file')
    res.setHeader('Content-Type', ({ '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.svg': 'image/svg+xml' })[extname(path)] || 'application/octet-stream')
    res.end(await readFile(path))
  } catch { res.writeHead(404); res.end('Not found') }
})
await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
const origin = `http://127.0.0.1:${server.address().port}`
const cache = new Map()
const errors = []
async function request(path) {
  if (!cache.has(path)) cache.set(path, fetch(origin + path).then(async r => {
    assert.equal(r.status, 200, `HTTP ${r.status}: ${path}`)
    return r.text()
  }))
  return cache.get(path)
}
const decode = s => s.replaceAll('&amp;', '&').replaceAll('&quot;', '"')
async function check(href, from) {
  if (!href || /^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(href)) return
  const url = new URL(decode(href), origin + from)
  if (url.origin !== origin) return
  assert.ok(url.pathname.startsWith(prefix), `Incorrect root asset/link: ${href} in ${from}`)
  const content = await request(url.pathname)
  if (url.hash && (!extname(url.pathname) || url.pathname.endsWith('.html') || url.pathname.endsWith('/'))) {
    const id = decodeURIComponent(url.hash.slice(1))
    assert.ok(content.includes(`id="${id}"`), `Missing anchor ${href} in ${from}`)
  }
}
try {
  for (const file of files) {
    const path = prefix + file.slice(root.length + 1).split('\\').join('/')
    await request(path)
    const content = await readFile(file, 'utf8')
    if (file.endsWith('.html')) {
      for (const match of content.matchAll(/\b(?:href|src)="([^"]+)"/g)) {
        try { await check(match[1], path) } catch (error) { errors.push(error.message) }
      }
    }
    if (file.endsWith('.css')) for (const match of content.matchAll(/url\(["']?([^"')]+)["']?\)/g)) {
      try { await check(match[1], path) } catch (error) { errors.push(error.message) }
    }
  }
  for (const page of ['', 'getting-started.html', 'installation.html', 'quickstart.html', 'commands.html', 'development/architecture.html', 'favicon.svg']) await request(prefix + page)
  const bundles = files.filter(f => f.endsWith('.js'))
  const js = (await Promise.all(bundles.map(f => readFile(f, 'utf8')))).join('\n')
  assert.match(js, /Ollama/, 'Search content must include Ollama')
  assert.match(js, /sessions/, 'Search content must include sessions')
  assert.ok(files.some(f => /(?:localSearch|LocalSearch|local-search)/i.test(f)), 'Local-search bundle must be generated')
  assert.ok(!files.some(f => /\/(audits|engineering)\//.test(f)), 'Internal records must not be generated')
  assert.ok(!errors.length, [...new Set(errors)].join('\n'))
  console.log(`Static HTTP smoke passed at ${prefix}: ${files.length} files; all HTML links, anchors, CSS assets and local-search bundle.`)
} finally {
  server.closeAllConnections()
  await new Promise(resolve => server.close(resolve))
}
