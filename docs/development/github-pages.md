# Publish with GitHub Pages

The documentation workflow builds a fully static VitePress website. It needs no server, personal access token, Java build or runtime backend.

## Repository metadata

The configured remote is `https://github.com/fpapon/syleks.git`. Its live default branch was verified as `main` during setup. The expected project-site address is [https://fpapon.github.io/syleks/](https://fpapon.github.io/syleks/). This is the configured destination; public deployment has not been verified by this implementation.

## One-time GitHub setup

1. Open **Settings → Pages** in `fpapon/syleks`.
2. Under **Build and deployment**, select **GitHub Actions** as the source.
3. Ensure Actions are enabled and repository policy permits the official actions used in `.github/workflows/docs.yml`.
4. Check that the `github-pages` environment permits deployments from `main`. Configure any required reviewers according to repository policy.
5. Merge or push these changes to `main`, or run **Documentation → Run workflow** on `main` with **Deploy after building** selected.
6. Open the deployment URL shown by the workflow and verify the homepage, nested pages, search and assets.

Repository Pages settings are not changed by the local build. `configure-pages` reads the existing configuration; it does not request automatic site creation.

## Workflow behavior

Pull requests run `npm ci`, source validation, a production build and static smoke checks with read-only repository permissions. Only pushes to `main`, or manual runs on `main` with deployment enabled, upload a Pages artifact and enter the deployment job. Manual runs on other branches can validate but cannot deploy.

The build job has `contents: read`. The deployment job alone receives `pages: write` and `id-token: write`, uses the `github-pages` environment, and invokes the official OIDC-backed Pages action. Action revisions are pinned to immutable commits, following the Java workflow convention. Deployments are serialized; PR builds use separate concurrency groups. npm caches download data, not installed modules. No secrets are passed to builds.

The artifact directory is `docs/.vitepress/dist`. The existing Java CI is unchanged. The documentation workflow deliberately has no path filters so links affected by repository changes are checked too.

## Base path and canonical URL

Local development defaults to `/`. Production sets `DOCS_BASE` to `/syleks/`; surrounding slashes are normalized. It must be a path, not an absolute URL. Links and assets use the configured base.

```sh
DOCS_BASE=/syleks/ npm run docs:build
DOCS_BASE=/syleks/ npm run docs:smoke
DOCS_BASE=/syleks/ npm run docs:preview
```

`DOCS_SITE_URL` controls the canonical URL and sitemap hostname; it defaults to `https://fpapon.github.io/syleks/`. Keep it consistent with the deployed URL. For a fork, update the repository, edit and source links in the VitePress config and guides, the workflow deployment branch if needed, and the production base and site URL. No Pages URL is embedded in internal navigation.

## Future custom domain

Configure a domain in GitHub Pages settings and its DNS records before switching deployment. Set the repository Actions variable `DOCS_BASE` to `/` and `DOCS_SITE_URL` to the full custom-domain URL ending in `/`. The workflow reads these variables and otherwise uses the current project-site defaults. No fictional domain or `CNAME` file is included.

## Troubleshooting deployment

- **Pages configuration lookup fails:** select GitHub Actions as the Pages source before running a deployment.
- **Permission or environment failure:** review repository Actions restrictions, environment branch rules and deployment approvals. Do not add a personal access token.
- **Missing CSS or scripts:** confirm `DOCS_BASE` matches the deployed path, rebuild and run the smoke check with the same value.
- **Missing page or anchor:** run `npm run docs:check`, rebuild, then run `npm run docs:smoke`; correct the source link rather than suppressing build errors.
- **Incorrect timestamps:** checkout must retain full Git history (`fetch-depth: 0`). Uncommitted new pages have no last-updated timestamp until committed.
- **Search has stale content:** ensure the latest successful artifact deployed, then reload. Search is built locally into static JavaScript, with no external service or API key.

For the upstream deployment model, see [GitHub's custom workflow documentation](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages) and the [VitePress deployment guide](https://vitepress.dev/guide/deploy).
