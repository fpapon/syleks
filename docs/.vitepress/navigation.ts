export const sidebar = [
  { text: 'Getting Started', items: [
    { text: 'Introduction', link: '/README' },
    { text: 'Installation', link: '/installation' },
    { text: 'Five-minute quickstart', link: '/quickstart' },
    { text: 'Setup and first conversation', link: '/getting-started' },
    { text: 'Keyboard and terminal controls', link: '/chat' }
  ] },
  { text: 'User Guide', collapsed: false, items: [
    { text: 'Sessions', link: '/sessions' },
    { text: 'Providers and models', link: '/providers' },
    { text: 'Authentication', link: '/authentication' },
    { text: 'Agents', link: '/agents' },
    { text: 'Subagents', link: '/subagents' },
    { text: 'Tools and permissions', link: '/tools-and-permissions' },
    { text: 'Remembered approvals', link: '/approvals' },
    { text: 'Command isolation', link: '/command-sandbox' },
    { text: 'MCP servers', link: '/mcp' },
    { text: 'Token usage', link: '/token-usage' },
    { text: 'Storage and privacy', link: '/storage-and-privacy' }
  ] },
  { text: 'Reference', collapsed: true, items: [
    { text: 'CLI and interactive commands', link: '/commands' },
    { text: 'Configuration and environment', link: '/configuration' },
    { text: 'Secret storage', link: '/secret-storage' },
    { text: 'Extensible tools', link: '/tools' },
    { text: 'Engineering project contracts (experimental)', link: '/project-contracts' }
  ] },
  { text: 'Troubleshooting', link: '/troubleshooting' },
  { text: 'Development', collapsed: true, items: [
    { text: 'Architecture and source builds', link: '/development/architecture' },
    { text: 'Contribute documentation', link: '/contributing-docs' },
    { text: 'GitHub Pages deployment', link: '/development/github-pages' }
  ] }
]
export const nav = [
  { text: 'Getting Started', link: '/quickstart', activeMatch: '/(quickstart|installation|getting-started)' },
  { text: 'Guides', link: '/README' },
  { text: 'Reference', link: '/commands' },
  { text: 'GitHub', link: 'https://github.com/fpapon/syleks' }
]
