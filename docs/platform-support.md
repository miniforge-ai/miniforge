<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Platform Support

Miniforge runs on macOS, Linux, and Windows. The runtime is Babashka (a
fast Clojure interpreter) plus a JVM uberjar for heavier work — both are
JVM-portable, so the workflow engine itself is the same code on every
platform. Differences are concentrated in install paths, the bash demo
script, and a handful of `bb` tasks that still assume a Unix shell.

## Status Matrix

| Platform              | Runtime | Install      | CI verified | Status        |
|-----------------------|---------|--------------|-------------|---------------|
| macOS arm64           | bb + JVM | Homebrew    | No (planned) | Stable       |
| macOS x86_64          | bb + JVM | Homebrew    | No (planned) | Stable       |
| Linux x86_64          | bb + JVM | static bb   | Yes (Ubuntu) | Stable       |
| Linux arm64           | bb + JVM | static bb   | No (planned) | Stable       |
| Windows x86_64 (native) | bb.exe + JVM | scoop  | Yes (preview, non-blocking) | **Beta** |
| Windows via WSL2      | bb + JVM | as Linux    | Covered by Linux runner | Stable |
| Windows via Git Bash  | bb + JVM | as Linux    | Not directly verified | Works     |

"Stable" means the install path is exercised by maintainers and CI catches
regressions. "Beta" means the path runs end-to-end for at least one tester
but isn't yet a release blocker — please report issues.

## macOS

```bash
brew install babashka/brew/babashka
git clone https://github.com/miniforge-ai/miniforge.git
cd miniforge
bb bootstrap   # installs Java, Clojure, linters via brew
```

`bb bootstrap` uses Homebrew. If you don't have brew, install Java 21,
Clojure CLI, and clj-kondo manually and skip bootstrap.

## Linux

```bash
# Static babashka — avoids glibc surprises across distros
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
chmod +x install
./install --static

# Java 21, Clojure CLI, clj-kondo via your package manager
# (apt, dnf, pacman, etc.) — see each project's docs.

git clone https://github.com/miniforge-ai/miniforge.git
cd miniforge
bb test        # bootstrap is brew-only today — skip on Linux
```

`bb bootstrap` is a Mac convenience task; on Linux, install dependencies
through your distro's package manager. The bb-based test suite runs
unmodified.

## Windows — Native (Beta)

### End users (`scoop install miniforge`)

Once `miniforge-ai/scoop-bucket` is provisioned (template lives at
[`scoop/`](../scoop/) — see its README for bootstrap steps), end users
install with:

```powershell
# One-time scoop install (skip if already present)
# If you hit an execution-policy error first:
#   Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
Invoke-Expression (New-Object System.Net.WebClient).DownloadString('https://get.scoop.sh')

# Add buckets and install. Babashka is pulled automatically as a dep.
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop bucket add miniforge https://github.com/miniforge-ai/scoop-bucket
scoop install miniforge

mf version
```

Upgrade later with `scoop update miniforge`.

### Contributors (cloning and running from source)

```powershell
# 1. Install scoop (skip if already installed)
# If you hit an execution-policy error first:
#   Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
Invoke-Expression (New-Object System.Net.WebClient).DownloadString('https://get.scoop.sh')

# 2. Install bb + Clojure tooling via scoop
scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure
scoop bucket add extras
scoop install babashka clojure clj-kondo

# 3. Clone and run
git clone https://github.com/miniforge-ai/miniforge.git
cd miniforge
bb test
```

### Known gaps on native Windows

- **`bb bootstrap`** — Mac-only today (uses `which` and `brew`). Install
  dependencies via scoop and skip the task. Cross-platform replacement is
  tracked under task B1/B2.
- **`examples/demo/run-demo.sh`** — Bash script. On native Windows, run it
  through Git Bash, or invoke the workflow directly:

  ```powershell
  mf run examples/demo/add-utility-function.edn
  ```

- **Path separators** — bb scripts use `babashka.fs` for path joining and
  should be platform-neutral. If you hit a hard-coded `/` in a script,
  please open an issue.
- **Symlinks** — Polylith generates symlinks under `projects/*/src`. On
  Windows, enable Developer Mode (Settings → Privacy & security → For
  developers) so non-admin processes can create symlinks, or run from an
  elevated shell.
- **Line endings** — set `git config --global core.autocrlf input` before
  cloning to keep LF in checked-in files; mismatched CRLF will fail
  `bb fmt:md` and pre-commit.

## Windows — WSL2 or Git Bash (Works Today)

If native scoop install fails or you hit a wall, fall back to a Unix shell
on Windows. Both are fully supported:

- **WSL2** — install Ubuntu from the Microsoft Store, then follow the
  Linux instructions inside the WSL shell. This is the path with the
  fewest surprises.
- **Git Bash** — bundled with Git for Windows. Install bb with the static
  install script (same as Linux). The bash demo script runs unmodified.

Both inherit the Linux CI coverage, so anything that passes on Ubuntu in
CI passes here.

## Verifying Your Setup

Run the platform check task — it reports OS, shell, bb/JVM versions, and
flags any missing dependencies:

```bash
bb check:platform
```

(Available from `bb` v1.3.0+; see task B1 if not yet present in your
checkout.)

## Reporting Windows Issues

The Windows native path is the newest. If you hit something that should
work, please open an issue with:

- `bb check:platform` output
- Shell (PowerShell, cmd, Git Bash, WSL — please be specific)
- The exact command and the full error
- Whether the same command works on macOS, Linux, or via WSL2

Tag the issue with `platform:windows` so we can track Windows fidelity
separately.
