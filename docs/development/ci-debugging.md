<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# CI Debugging Guide

This guide explains how to access and use CI logs when debugging build, test, or lint failures.

## Quick Reference

When CI fails and you can't reproduce locally:

1. **Check the Actions tab** in GitHub
2. **Download log artifacts** for the failed job
3. **Review the detailed logs** (lint, test, or build logs)

## Log Artifacts

All CI jobs capture detailed logs and upload them as artifacts:

### Lint Job

- **clj-kondo.log** - Clojure linting output with file-by-file errors
- **markdownlint.log** - Markdown formatting issues

### Test Job

- **test-output.log** - Full test execution output

### Build Job

- **compile.log** - Compilation output (syntax checking)
- **build-cli.log** - CLI build process output
- **cli-test.log** - CLI smoke test results

## Accessing Logs

### From GitHub UI

1. Navigate to the PR or commit
2. Click on the failing CI check
3. Click "Details" to view the workflow run
4. Scroll to the bottom of the page
5. Find "Artifacts" section
6. Download the relevant log artifact (e.g., `lint-logs-<sha>`)

### From GitHub CLI

```bash
# List artifacts for a PR
gh run list --repo miniforge-ai/miniforge --branch feat/your-branch

# Download all artifacts for a specific run
gh run download <run-id> --repo miniforge-ai/miniforge

# Download specific artifact
gh run download <run-id> --name lint-logs-<sha>
```

### Example

```bash
# PR #25 failed linting
gh pr checks 25

# Find the run ID and download logs
gh run download 21197679759 --name lint-logs-c1cb06c
```

## Understanding Log Output

### clj-kondo.log

Format:

```text
<file>:<line>:<col>: error: <message>
```

Example:

```text
components/heuristic/test/core_test.clj:53:7: error: save-heuristic is called with 5 args but expects 4
```

Exit codes:

- `0` - No issues
- `2` - Warnings only (allowed)
- `3` - Errors found (fails CI)

### test-output.log

Contains:

- Test execution command
- Per-component test results
- Assertion failures with stack traces
- Test timing information

### Build Logs

- **compile.log** - Shows syntax errors before bytecode generation
- **build-cli.log** - Shows uberscript bundling process
- **cli-test.log** - Basic smoke tests (version, help)

## Common Issues

### Issue: "Exit code 3" with no visible errors

**Solution**: Download the lint logs artifact. The error output may not appear in the
GitHub UI but will be in the log file.

**Example**: PR #25 initially showed only "Exit code 3" - the log artifact contained
the actual arity mismatch errors.

### Issue: Tests pass locally but fail in CI

**Possible causes**:

- Different dependency versions (check cache)
- Environment-specific behavior
- Timing issues in tests

**Solution**: Download test-output.log and compare with local test output

### Issue: Build fails in CI but succeeds locally

**Possible causes**:

- Uncommitted files being used locally
- Different JVM versions
- Missing dependencies in deps.edn

**Solution**: Download compile.log and build-cli.log to see exact compilation errors

## Log Retention

- **Retention period**: 7 days
- **Naming**: `<job>-logs-<git-sha>`
- **Format**: Plain text files (`.log`)

## Verbose Mode

All bb tasks now output verbose information:

```bash
# Local testing with same verbosity as CI
bb lint:clj:all 2>&1 | tee clj-kondo.log
bb test 2>&1 | tee test-output.log
bb compile 2>&1 | tee compile.log
```

This allows you to replicate CI logging locally for comparison.

## GitHub Actions Groups

CI output uses collapsible groups for readability:

```yaml
echo "::group::Clojure Linting"
bb lint:clj:all
echo "::endgroup::"
```

These groups are automatically collapsed in the GitHub UI but fully expanded
in the downloadable log files.

## Best Practices

1. **Always download logs first** before asking for help with CI failures
2. **Compare local vs CI logs** to identify environment differences
3. **Check recent changes** to bb.edn or .github/workflows/ci.yml
4. **Verify all files are committed** if CI fails but local succeeds
5. **Use the same commands** that CI uses (see bb.edn tasks)

## Related Documentation

- [Pre-commit Hooks](../../specs/archived/phase1-postmortem.md#making-hooks-non-optional)
- [Phase 1 Postmortem](../../specs/archived/phase1-postmortem.md) - Why we need good logging
- [CI Workflow](../../.github/workflows/ci.yml) - Current CI configuration
