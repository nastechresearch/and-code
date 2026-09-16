# Upstream sync and fork rebranding

This fork keeps the upstream repository as a separate source and reapplies the `nastechresearch/and-code` repository branding after each sync. External GitHub links remain unchanged; only links and references owned by the original `yuga-hashimoto/and-code` repository are rewritten.

## Run a sync

Open **Actions → Sync upstream and rebrand fork → Run workflow**. The default source is the upstream `main` branch. A tag or another upstream ref can be entered when a specific release needs to be synchronized.

The workflow:

1. Fetches the selected ref from `https://github.com/yuga-hashimoto/and-code.git`.
2. Creates an isolated `automation/sync-upstream-*` branch.
3. Merges the upstream ref into the fork.
4. Runs `scripts/rebrand_fork.sh`.
5. Verifies that original repository links are gone.
6. Pushes the branch to `nastechresearch/and-code`.
7. Opens or updates a pull request against `main`.

Use the **dry run** option to preview the rebrand without pushing or opening a pull request. Merge conflicts stop the workflow for manual resolution; the workflow never force-pushes `main`.

## Local preview

```bash
DRY_RUN=true scripts/rebrand_fork.sh
```

The script intentionally does not rewrite `github.com` globally. It preserves links to third-party projects such as OpenCode, Claude Code, Google Antigravity, and the OpenCode runtime release archives.
