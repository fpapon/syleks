import { defineConfig } from 'vitepress'
import { nav, sidebar } from './navigation'

const rawBase = process.env.DOCS_BASE?.trim() || '/'
if (/[?#:\\]/.test(rawBase) || rawBase.split('/').some(p => p === '..' || p === '.')) {
  throw new Error('DOCS_BASE must be a URL path, for example /syleks/')
}
const base = '/' + rawBase.split('/').filter(Boolean).join('/') + '/'
const normalizedBase = base === '//' ? '/' : base
const site = (process.env.DOCS_SITE_URL || 'https://fpapon.github.io/syleks/').replace(/\/?$/, '/')

export default defineConfig({
  title: 'Syleks',
  description: 'A terminal-native AI coding assistant with persistent sessions, permission-checked tools and MCP integration.',
  lang: 'en-US',
  base: normalizedBase,
  appearance: 'dark',
  lastUpdated: true,
  srcExclude: ['audits/**', 'engineering/**'],
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: normalizedBase + 'favicon.svg' }],
    ['meta', { property: 'og:title', content: 'Syleks documentation' }],
    ['meta', { property: 'og:description', content: 'Get started with your terminal AI coding assistant.' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { name: 'theme-color', content: '#10151D' }]
  ],
  sitemap: { hostname: site },
  transformHead({ pageData }) {
    const path = pageData.relativePath.replace(/\.md$/, '.html').replace(/^index\.html$/, '')
    return [['link', { rel: 'canonical', href: new URL(path, site).href }]]
  },
  markdown: { lineNumbers: false, theme: { light: 'github-light', dark: 'github-dark' } },
  themeConfig: {
    logo: { src: '/favicon.svg', alt: 'Syleks terminal mark' },
    nav, sidebar,
    search: { provider: 'local' },
    outline: { level: [2, 3], label: 'On this page' },
    socialLinks: [{ icon: 'github', link: 'https://github.com/fpapon/syleks' }],
    editLink: { pattern: 'https://github.com/fpapon/syleks/edit/main/docs/:path', text: 'Improve this page on GitHub' },
    lastUpdated: { text: 'Last updated' },
    footer: {
      message: '<a href="https://github.com/fpapon/syleks">GitHub project</a> · <a href="https://github.com/fpapon/syleks/blob/main/LICENSE">Apache-2.0</a> · <a href="https://github.com/fpapon/syleks/blob/main/docs/contributing-docs.md">Contribute documentation</a>',
      copyright: 'Syleks · Built for the terminal.'
    }
  }
})
