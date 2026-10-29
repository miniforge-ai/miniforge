<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Release Process

This document describes how to create and publish a new release of the **Miniforge** SDLC product (CLI/TUI). MiniForge
Core ships as part of the monorepo and is versioned alongside Miniforge. Data Foundry does not yet have a separate
release process.

## Prerequisites

### 1. Create Homebrew Tap Repository

Create a new repository at `github.com/miniforge-ai/homebrew-tap`:

```bash
# On GitHub, create miniforge-ai/homebrew-tap repository
# Then initialize it locally:
mkdir homebrew-tap
cd homebrew-tap
git init
mkdir Formula
echo "# Homebrew Tap for miniforge" > README.md
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin git@github.com:miniforge-ai/homebrew-tap.git
git push -u origin main
```

### 2. Create GitHub App for Homebrew Tap Updates

Create a GitHub App to automate Homebrew formula updates:

#### Step 1: Create the GitHub App

1. Go to your organization settings: `https://github.com/organizations/miniforge-ai/settings/apps`
2. Click "New GitHub App"
3. Configure the app:
   - **Name**: `miniforge-homebrew-updater` (or any name you prefer)
   - **Description**: `Automated updates to miniforge Homebrew formula`
   - **Homepage URL**: `https://github.com/miniforge-ai/miniforge`
   - **Webhook**: Uncheck "Active" (not needed for this use case)
   - **Repository permissions**:
     - Contents: `Read and write` (required to push formula updates)
   - **Where can this GitHub App be installed?**: `Only on this account`
4. Click "Create GitHub App"

#### Step 2: Generate Private Key

1. After creating the app, scroll down to "Private keys"
2. Click "Generate a private key"
3. Save the downloaded `.pem` file securely

#### Step 3: Install the App

1. Go to the app's settings page
2. Click "Install App" in the left sidebar
3. Select `miniforge-ai` organization
4. Choose "Only select repositories"
5. Select `homebrew-tap` repository
6. Click "Install"

#### Step 4: Add Secrets to miniforge Repository

Add the following secrets to `miniforge-ai/miniforge`:

1. **HOMEBREW_APP_ID**:
   - Find the App ID on the app settings page (near the top)
   - Add as repository secret

2. **HOMEBREW_APP_PRIVATE_KEY**:
   - Open the `.pem` file you downloaded
   - Copy the entire contents (including `-----BEGIN RSA PRIVATE KEY-----` and `-----END RSA PRIVATE KEY-----`)
   - Add as repository secret

To add secrets:

- Go to `https://github.com/miniforge-ai/miniforge/settings/secrets/actions`
- Click "New repository secret"
- Add both secrets

#### Why GitHub App vs Personal Access Token?

GitHub Apps are superior to personal access tokens because:

- **Granular permissions**: Only access to `homebrew-tap` repository
- **Not tied to user**: Continues working if team members leave
- **Better audit trail**: Actions appear as the app, not a user
- **Automatic token rotation**: Tokens expire after 1 hour
- **Organization-level**: Managed at org level, not user level

## Release Types

### Automated Release (Recommended)

From a clean `main` whose latest commit has passed CI, run:

```bash
bb release
```

`bb release` runs no tests. It refuses unless you are on `main`, the working tree is clean, and the newest `ci.yml`
push run on `main` for `HEAD` passed. It then tags `vYYYY.MM.DD.N` plus a `stable-YYYYMMDD` tag and pushes both.

Pushing a version tag by hand does the same as far as the release workflow is concerned:

```bash
# Determine version (DateVer: YYYY.MM.DD.N)
VERSION="2026.01.20.1"

# Create and push tag
git tag -a "v${VERSION}" -m "Release ${VERSION}"
git push origin "v${VERSION}"
```

The release workflow (`.github/workflows/release.yml`) then:

1. Refuses to continue (`gate` job) unless the tagged commit is on `main` and its newest `ci.yml` push run on `main`
   passed. It waits up to 40 minutes for a run that is still going.
2. Rejects a version that is not DateVer.
3. Builds the CLI jar on macOS arm64 and Linux x86_64, with the version stamped into it, and checks that the built
   jar's `version` command reports that version. Builds the Windows x86_64 zip (preview; its failure does not block
   the release).
4. Generates SHA256 checksums
5. Creates a GitHub release with the assets
6. Updates the Homebrew formula in `homebrew-tap`
7. Triggers the Scoop bucket's autoupdate, only when the Windows build succeeded

### Manual Release

Trigger a release via GitHub Actions UI:

1. Go to Actions → Release
2. Click "Run workflow"
3. Enter the version (e.g., `2026.01.20.1`)
4. Click "Run workflow"

## Version Numbering

miniforge uses **DateVer** versioning: `YYYY.MM.DD.N`

- `YYYY`: Year (e.g., 2026)
- `MM`: Month (01-12)
- `DD`: Day (01-31)
- `N`: Build number for the day (1, 2, 3, ...)

Examples:

- `2026.01.20.1` - First release on January 20, 2026
- `2026.01.20.2` - Second release on the same day
- `2026.02.15.1` - First release on February 15, 2026

## Verification

After the release workflow completes:

### 1. Check GitHub Release

Visit `https://github.com/miniforge-ai/miniforge/releases` and verify:

- Release was created with correct version
- All 6 assets are present (4 stable + 2 Windows preview). The macOS and Linux assets are Babashka uberjars (the same
  portable jar, built on each runner), not native executables:
  - `miniforge-macos-arm64`
  - `miniforge-macos-arm64.sha256`
  - `miniforge-linux-x86_64`
  - `miniforge-linux-x86_64.sha256`
  - `miniforge-windows-x86_64.zip` (preview)
  - `miniforge-windows-x86_64.zip.sha256` (preview)

### 2. Check Homebrew Formula

Visit `https://github.com/miniforge-ai/homebrew-tap` and verify:

- `Formula/miniforge.rb` was updated
- Version matches the release
- SHA256 checksums are present for all platforms

### 3. Test Installation

#### Homebrew (recommended for end users)

```bash
# Add tap
brew tap miniforge-ai/tap

# Install
brew install miniforge

# Verify (prints the release version)
mf version
```

#### Direct Download

The jar runs under Babashka (`bb` on your PATH). Download it under its asset name so the `.sha256` file matches, verify
it, then install it behind an `mf` launcher:

```bash
# macOS ARM64 (use miniforge-linux-x86_64 on Linux, and sha256sum -c instead of shasum)
ASSET=miniforge-macos-arm64
curl -fLO https://github.com/miniforge-ai/miniforge/releases/download/v2026.01.20.1/$ASSET
curl -fLO https://github.com/miniforge-ai/miniforge/releases/download/v2026.01.20.1/$ASSET.sha256
shasum -a 256 -c "$ASSET.sha256"

mkdir -p ~/.local/lib/miniforge ~/.local/bin
mv "$ASSET" ~/.local/lib/miniforge/miniforge.jar
cat > ~/.local/bin/mf <<'SH'
#!/bin/sh
exec bb --jar "$HOME/.local/lib/miniforge/miniforge.jar" -m ai.miniforge.cli.main "$@"
SH
chmod +x ~/.local/bin/mf
mf version   # needs ~/.local/bin on your PATH
```

```powershell
# Windows x86_64 (preview)
Invoke-WebRequest -Uri https://github.com/miniforge-ai/miniforge/releases/download/v2026.01.20.1/miniforge-windows-x86_64.zip -OutFile miniforge.zip
Expand-Archive miniforge.zip -DestinationPath miniforge
.\miniforge\mf.cmd version

# Verify checksum
Invoke-WebRequest -Uri https://github.com/miniforge-ai/miniforge/releases/download/v2026.01.20.1/miniforge-windows-x86_64.zip.sha256 -OutFile miniforge.zip.sha256
Get-FileHash miniforge.zip -Algorithm SHA256 | Format-List
# Compare against the value in miniforge.zip.sha256 by eye, or:
#   (Get-FileHash miniforge.zip -Algorithm SHA256).Hash -eq (Get-Content miniforge.zip.sha256).Split()[0]
```

## Post-Release

### 1. Announce Release

Post release notes to:

- GitHub Discussions
- Project README (if major version)
- Community channels

### 2. Update Documentation

If the release includes new features:

- Update README.md
- Update docs/ as needed
- Add migration guides if breaking changes

## Rollback

If a release has critical issues:

### 1. Delete GitHub Release

```bash
VERSION="2026.01.20.1"
gh release delete "v${VERSION}" --yes
git push --delete origin "v${VERSION}"
```

### 2. Revert Homebrew Formula

```bash
cd homebrew-tap
git revert HEAD
git push
```

### 3. Create Hotfix Release

Fix the issue and create a new release with incremented build number:

```bash
VERSION="2026.01.20.2"
git tag -a "v${VERSION}" -m "Hotfix: ${VERSION}"
git push origin "v${VERSION}"
```

## Troubleshooting

### Release Workflow Failed

1. Check GitHub Actions logs
2. Common issues:
   - `gate` failed: the tagged commit is not on `main`, or its `ci.yml` run did not pass (or was still running after
     40 minutes). Fix `main` or wait for CI, then re-run the workflow or tag a passing commit.
   - `Determine version` failed: the tag or dispatch input is not DateVer (`YYYY.MM.DD.N`)
   - `Smoke-test the built jar` failed: the jar does not report the release version
   - Missing `HOMEBREW_APP_ID` / `HOMEBREW_APP_PRIVATE_KEY` secrets
   - Homebrew tap repository doesn't exist
   - Build failures on specific platforms

### Homebrew Formula Not Updated

1. Check if GitHub App is properly configured:
   - Verify `HOMEBREW_APP_ID` and `HOMEBREW_APP_PRIVATE_KEY` secrets exist
   - Ensure the app is installed on the `homebrew-tap` repository
   - Verify the app has `Contents: Read and write` permission
2. Check release workflow logs for the homebrew job
3. Verify the private key format is correct (includes BEGIN/END lines)

### SHA256 Mismatch

If users report checksum errors:

1. Verify assets were uploaded correctly
2. Regenerate checksums:

   ```bash
   shasum -a 256 dist/miniforge-* > checksums.txt
   ```

3. Update formula manually if needed

## Development Builds

For testing without creating a release:

```bash
# Build locally (reports version "dev"; MINIFORGE_VERSION=2026.01.20.1 bb build:cli stamps a version)
bb build:cli

# Test
bb --jar dist/miniforge.jar -m ai.miniforge.cli.main version
bb --jar dist/miniforge.jar -m ai.miniforge.cli.main help
```

To distribute development builds:

```bash
# Upload to a temporary location
scp dist/miniforge.jar user@server:/tmp/miniforge-dev.jar

# Or use GitHub Actions artifacts from CI builds
```
