<!--
  This is the contents of the miniforge-ai/scoop-bucket repository,
  staged here in the main miniforge repo as a template. It is NOT used
  from this location — copy these files into the new bucket repo when
  provisioning. See provisioning notes at the bottom.
-->

# miniforge-ai/scoop-bucket

[Scoop](https://scoop.sh) bucket for installing
[Miniforge](https://github.com/miniforge-ai/miniforge) on Windows.

## Install

```powershell
# One-time bucket setup
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop bucket add miniforge https://github.com/miniforge-ai/scoop-bucket

# Install miniforge (pulls babashka automatically as a dependency)
scoop install miniforge

mf version
```

## Update

```powershell
scoop update miniforge
```

## How autoupdate works

`bucket/miniforge.json` declares `checkver` and `autoupdate` blocks
pointing at the miniforge release feed. The `Autoupdate` workflow
(`.github/workflows/autoupdate.yml`) refreshes the manifest on three
triggers:

1. **`repository_dispatch`** — fired by miniforge's release.yml on each
   `v*` tag (instant — within ~10s of the release publishing).
2. **`workflow_dispatch`** — manual via the Actions UI.
3. **`schedule`** — daily at 06:00 UTC as a safety net.

Public-repo Actions runners are free, so neither the cron nor the
dispatch run costs anything against any minute budget.

## Provisioning notes (delete this section after first commit)

This bucket repo was bootstrapped from the template at
`scoop/` in [`miniforge-ai/miniforge`](https://github.com/miniforge-ai/miniforge).
To bring it online:

1. **Create the public repo** on GitHub:
   `gh repo create miniforge-ai/scoop-bucket --public --description "Scoop bucket for miniforge"`
2. **Copy** `scoop/bucket/miniforge.json` and `scoop/.github/workflows/autoupdate.yml`
   from miniforge's main branch into the new repo. The `bucket/` and
   `.github/workflows/` paths must be at the bucket repo's root.
3. **Manual first-run**: in the new repo's Actions tab, run the
   `Autoupdate` workflow via `workflow_dispatch`. This populates
   `bucket/miniforge.json` with the latest release version and SHA256.
4. (Optional) **Wire up `repository_dispatch`** in miniforge's
   `release.yml` so the bucket updates instantly on each release —
   one-line follow-up, not blocking.
5. Once the manifest has a real version (not `0.0.0-template`), test
   end-to-end on a Windows machine:

   ```powershell
   scoop bucket add miniforge https://github.com/miniforge-ai/scoop-bucket
   scoop install miniforge
   mf version
   ```
