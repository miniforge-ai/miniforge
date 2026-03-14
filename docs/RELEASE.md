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

Push a version tag to trigger the release workflow:

```bash
# Determine version (DateVer: YYYY.MM.DD.N)
VERSION="2026.01.20.1"

# Create and push tag
git tag -a "v${VERSION}" -m "Release ${VERSION}"
git push origin "v${VERSION}"
```

This will automatically:

1. Build binaries for all platforms (macOS arm64/x86, Linux arm64/x86)
2. Generate SHA256 checksums
3. Create a GitHub release with assets
4. Update the Homebrew formula in `homebrew-tap`

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
- All 8 assets are present:
  - `miniforge-macos-arm64`
  - `miniforge-macos-arm64.sha256`
  - `miniforge-macos-x86_64`
  - `miniforge-macos-x86_64.sha256`
  - `miniforge-linux-arm64`
  - `miniforge-linux-arm64.sha256`
  - `miniforge-linux-x86_64`
  - `miniforge-linux-x86_64.sha256`

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

# Verify
miniforge version
```

#### Direct Download

```bash
# macOS ARM64
curl -L https://github.com/miniforge-ai/miniforge/releases/download/v2026.01.20.1/miniforge-macos-arm64 -o miniforge
chmod +x miniforge
./miniforge version

# Verify checksum
curl -L https://github.com/miniforge-ai/miniforge/releases/download/v2026.01.20.1/miniforge-macos-arm64.sha256 -o miniforge.sha256
shasum -a 256 -c miniforge.sha256
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
   - Missing `HOMEBREW_TAP_TOKEN` secret
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
# Build locally
bb build:cli

# Test
./dist/miniforge version
./dist/miniforge help
```

To distribute development builds:

```bash
# Upload to a temporary location
scp dist/miniforge user@server:/tmp/miniforge-dev

# Or use GitHub Actions artifacts from CI builds
```
