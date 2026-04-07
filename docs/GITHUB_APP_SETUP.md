<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# GitHub App Setup for Homebrew Automation

This guide explains how to set up a GitHub App for automated Homebrew formula updates instead of using a personal access token.

## Why GitHub App?

GitHub Apps are the recommended approach for automation because they:

- **Granular permissions**: Only access to specific repositories (`homebrew-tap`)
- **Not tied to a user**: Continues working even if team members leave
- **Better audit trail**: Actions appear as the app, not an individual user
- **Automatic token rotation**: Tokens expire after 1 hour (more secure)
- **Organization-level management**: Managed at the org level, not user level
- **No rate limit impact on users**: App has its own rate limit

## Prerequisites

- Organization admin access to `miniforge-ai`
- Repository admin access to `miniforge-ai/miniforge`
- The `miniforge-ai/homebrew-tap` repository must exist

## Step-by-Step Setup

### 1. Create the GitHub App

1. Navigate to: `https://github.com/organizations/miniforge-ai/settings/apps`
2. Click **"New GitHub App"**
3. Fill in the following settings:

   **Basic Information:**
   - **GitHub App name**: `miniforge-homebrew-updater`
   - **Description**: `Automated updates to miniforge Homebrew formula`
   - **Homepage URL**: `https://github.com/miniforge-ai/miniforge`

   **Webhook:**
   - Uncheck **"Active"** (webhooks not needed for this use case)

   **Repository permissions:**
   - **Contents**: `Read and write`
     - This allows the app to push formula updates to the tap repository

   **Where can this GitHub App be installed?:**
   - Select **"Only on this account"**

4. Click **"Create GitHub App"**

### 2. Generate Private Key

1. After creating the app, you'll be on the app's settings page
2. Scroll down to the **"Private keys"** section
3. Click **"Generate a private key"**
4. A `.pem` file will download automatically
5. **Store this file securely** - you'll need it in Step 4

### 3. Install the App on homebrew-tap

1. From the app settings page, click **"Install App"** in the left sidebar
2. Click **"Install"** next to the `miniforge-ai` organization
3. On the installation page:
   - Select **"Only select repositories"**
   - Choose **`homebrew-tap`** from the dropdown
4. Click **"Install"**

### 4. Add Secrets to the miniforge Repository

You need to add two secrets to the main `miniforge-ai/miniforge` repository:

#### 4a. Add HOMEBREW_APP_ID

1. Go back to the app settings page
2. Note the **App ID** at the top of the page (it's a number like `123456`)
3. Navigate to: `https://github.com/miniforge-ai/miniforge/settings/secrets/actions`
4. Click **"New repository secret"**
5. Name: `HOMEBREW_APP_ID`
6. Value: The App ID number
7. Click **"Add secret"**

#### 4b. Add HOMEBREW_APP_PRIVATE_KEY

1. Open the `.pem` file you downloaded in Step 2 with a text editor
2. Copy the **entire contents** including:

   ```
   -----BEGIN RSA PRIVATE KEY-----
   [key content]
   -----END RSA PRIVATE KEY-----
   ```

3. Navigate to: `https://github.com/miniforge-ai/miniforge/settings/secrets/actions`
4. Click **"New repository secret"**
5. Name: `HOMEBREW_APP_PRIVATE_KEY`
6. Value: Paste the entire contents of the `.pem` file
7. Click **"Add secret"**

### 5. Verify the Setup

To verify everything is configured correctly:

1. Check that both secrets exist:
   - Go to `https://github.com/miniforge-ai/miniforge/settings/secrets/actions`
   - You should see `HOMEBREW_APP_ID` and `HOMEBREW_APP_PRIVATE_KEY` listed

2. Check that the app is installed:
   - Go to `https://github.com/organizations/miniforge-ai/settings/installations`
   - You should see `miniforge-homebrew-updater` with access to `homebrew-tap`

3. Test with a release:
   - Create a test tag: `git tag v0.0.1 && git push origin v0.0.1`
   - Watch the GitHub Actions workflow
   - Verify the formula is updated in `homebrew-tap`

## How It Works

When the release workflow runs:

1. The workflow uses `actions/create-github-app-token@v1` to generate a temporary token
2. The token is created using the App ID and Private Key
3. The token has permissions based on what you granted the app (Contents: Read and write)
4. The token is scoped to only the `homebrew-tap` repository
5. The workflow uses this token to checkout, modify, and push to `homebrew-tap`
6. The token automatically expires after 1 hour

## Troubleshooting

### Release workflow fails with "Resource not accessible by integration"

**Cause**: The app doesn't have the correct permissions.

**Solution**:

1. Go to the app settings: `https://github.com/organizations/miniforge-ai/settings/apps/miniforge-homebrew-updater`
2. Click **"Permissions & events"**
3. Ensure **"Contents"** is set to **"Read and write"**
4. Save changes

### Release workflow fails with "Bad credentials"

**Cause**: The `HOMEBREW_APP_PRIVATE_KEY` is incorrectly formatted.

**Solution**:

1. Re-download or regenerate the private key
2. Ensure you copied the **entire** file including BEGIN/END lines
3. No extra spaces or newlines at the beginning or end
4. Update the secret in GitHub

### Workflow can't find the app

**Cause**: The app isn't installed on the `homebrew-tap` repository.

**Solution**:

1. Go to: `https://github.com/organizations/miniforge-ai/settings/installations`
2. Click on your app
3. Click **"Configure"**
4. Ensure `homebrew-tap` is selected
5. Save changes

## Security Best Practices

1. **Never commit the private key** to version control
2. **Rotate keys regularly**: Generate a new private key periodically
3. **Minimal permissions**: Only grant what's needed (Contents: Read and write)
4. **Repository scope**: Only install on repositories that need it
5. **Audit access**: Periodically review the app's installations and permissions
6. **Use secret scanning**: Enable secret scanning on your repositories

## Updating the App

If you need to change permissions or settings:

1. Go to: `https://github.com/organizations/miniforge-ai/settings/apps/miniforge-homebrew-updater`
2. Make your changes
3. Click **"Save changes"**
4. If you changed permissions, users may need to re-approve the app installation

## Revoking Access

To revoke the app's access:

1. Go to: `https://github.com/organizations/miniforge-ai/settings/installations`
2. Click on `miniforge-homebrew-updater`
3. Click **"Configure"**
4. Scroll down and click **"Uninstall"**

Or delete the app entirely:

1. Go to: `https://github.com/organizations/miniforge-ai/settings/apps/miniforge-homebrew-updater`
2. Click **"Advanced"** tab
3. Click **"Delete GitHub App"**

## References

- [GitHub Apps Documentation](https://docs.github.com/en/apps)
- [actions/create-github-app-token](https://github.com/actions/create-github-app-token)
- [Authenticating with GitHub Apps](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app)
