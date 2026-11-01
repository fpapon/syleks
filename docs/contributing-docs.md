# Contribute documentation

The website uses the existing `docs/` Markdown tree as its source of truth. Java CLI users do not need Node.js. Website contributors need Node.js 22.14 or newer; CI uses Node.js 24 LTS and npm.

## Preview a change

From the repository root:

```sh
npm ci
npm run docs:dev
```

Open the local URL printed by VitePress. Markdown edits reload automatically. Check light and dark themes, keyboard focus, narrow screens and code block scrolling.

To build and preview the static output:

```sh
npm run docs:check
npm run docs:build
npm run docs:preview
```

The output is `docs/.vitepress/dist/`. Never edit or commit generated HTML.

## Add a page

1. Create a Markdown file in `docs/` or a purpose-specific subdirectory. Use a descriptive level-one title and a short introduction.
2. Add it to the curated groups in `docs/.vitepress/navigation.ts`. Navigation links use site paths such as `/sessions`, without the deployment base.
3. Link related pages with relative Markdown links, for example `[Sessions](sessions.md)`.
4. Run the source checks and the production checks below.

Keep procedures short and include expected results. Verify commands and configuration against code before publishing. Label experimental behavior and avoid empty pages or future features described as available. Use fenced `sh`, `java`, `json`, `yaml` or `diff` blocks. Use VitePress `::: tip` / `::: warning` containers for callouts. Public pages currently need no Mermaid plugin; historical Mermaid diagrams remain in excluded audits.

## Links and screenshots

Use relative links for generated pages and explicit GitHub links for repository files outside `docs/` or in excluded records. Give links descriptive labels. Put original SVGs or screenshots in `docs/public/` and use meaningful alt text. Markdown asset links such as `![Description](/screenshot.png)` receive the VitePress base automatically. In Vue bindings use VitePress `withBase()` rather than hardcoded root URLs.

Do not include credentials, real customer information, confidential code or private session exports. Crop screenshots to the relevant interface and review terminal content before committing.

## Validate a production build

```sh
npm run docs:check
DOCS_BASE=/syleks/ npm run docs:build
DOCS_BASE=/syleks/ npm run docs:smoke
DOCS_BASE=/syleks/ npm run docs:preview
```

Open the preview URL under `/syleks/`. Source checks validate page titles, local links, assets and navigation. The smoke check serves the generated site temporarily and checks internal pages, generated anchors, asset requests, CSS URLs and the local-search bundle without contacting third-party sites. Also test search for **Ollama** and **sessions** in a browser. External link availability is intentionally outside CI to avoid network flakiness.

## Dependency maintenance

VitePress is pinned to stable `1.6.4`. The npm override selects Vite `6.4.5` because the default Vite 5 dependency has unresolved development-server advisories; the override is validated by the static build and smoke checks. Markdown parsing is pinned to patched `14.3.3`. Keep the lockfile committed, use `npm ci`, and recheck compatibility and `npm audit` when upgrading. No prerelease VitePress dependency is used.

This VitePress/Vite combination can emit a development-only warning about the `/@localSearchIndex` hot-reload handler. Development search and production search have been checked in a browser and work. If search results become stale while editing, restart the development server. Production builds do not emit this warning.

## Publication boundaries

`docs/audits/**` contains historical parity/security audits and test evidence; `docs/engineering/**` contains internal development records and proposed contracts. Both are excluded from page generation and local search. They remain in the repository and can be linked on GitHub. Do not put private material in `docs/public/`: that directory is copied verbatim. The root README, examples and agent prompts remain repository documents, not duplicated website trees.

## Submit improvements

Keep changes focused, explain the user-visible improvement in your pull request, and include validation results. Include desktop and mobile screenshots for visible theme changes. [Deployment instructions](development/github-pages.md) describe the separate GitHub Pages workflow.
