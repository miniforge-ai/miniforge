# miniforge.ai — Application Architecture Specification

**Version:** 0.1.0  
**Status:** Draft  
**Date:** 2026-01-18  

---

## 1. Overview

### 1.1 Repository Structure

miniforge is a **Polylith monorepo** that produces multiple artifacts:

| Artifact | Technology | Distribution | Use Case |
|----------|------------|--------------|----------|
| `miniforge` CLI | Babashka uberscript | Homebrew, curl | Production CLI |
| `miniforge.jar` | JVM uberjar | Docker, direct | Server deployments |
| `miniforge-fleet.jar` | JVM uberjar | Docker | Fleet mode daemon |
| Development scripts | Babashka (dev paths) | Local | Development/testing |

### 1.2 Design Principles

1. **Babashka-first**: Core logic runs in both BB and JVM
2. **Single-file distribution**: CLI is one executable file
3. **Polylith components**: Shared across all build targets
4. **Stratified within components**: Max 3 layers per file

---

## 2. Polylith Workspace

### 2.1 Top-Level Structure

```
miniforge/
├── workspace.edn          # Polylith config (top-ns: ai.miniforge)
├── deps.edn               # Root deps, aliases
├── bb.edn                 # Babashka tasks
├── build.clj              # Build tooling
│
├── components/            # Shared libraries (interfaces + implementations)
│   ├── agent/
│   ├── artifact/
│   ├── control-plane/
│   ├── heuristic/
│   ├── llm/
│   ├── logging/
│   ├── plugin/
│   ├── policy/
│   ├── pr-loop/
│   ├── schema/
│   ├── task/
│   ├── tool/
│   └── workflow/
│
├── bases/                 # Entry points (main namespaces)
│   ├── cli/               # miniforge CLI (BB-compatible)
│   ├── server/            # HTTP API server (JVM-only)
│   └── fleet/             # Fleet daemon (JVM-only)
│
├── projects/              # Deployable artifacts
│   ├── miniforge/         # CLI project (BB uberscript + JVM jar)
│   ├── miniforge-server/  # API server (JVM jar)
│   └── miniforge-fleet/   # Fleet daemon (JVM jar)
│
├── development/           # REPL environment
│   └── src/
│
├── dist/                  # Built artifacts
│   ├── miniforge          # BB uberscript
│   ├── miniforge.jar      # JVM uberjar
│   └── miniforge-fleet.jar
│
└── docs/
    └── specs/
```

### 2.2 Namespace Convention

```
ai.miniforge.<component>.<module>

Examples:
  ai.miniforge.agent.interface      # Component public API
  ai.miniforge.agent.core           # Implementation
  ai.miniforge.agent.spec           # Specs/schemas
  ai.miniforge.cli.main             # Base entry point
```

---

## 3. Component Catalog

### 3.1 Stratum Map

```
┌─────────────────────────────────────────────────────────────────────────┐
│ BASES (Entry Points)                                                     │
│  cli, server, fleet                                                      │
├─────────────────────────────────────────────────────────────────────────┤
│ ADAPTERS                                                                 │
│  (within components: HTTP handlers, CLI parsers, GitHub client)          │
├─────────────────────────────────────────────────────────────────────────┤
│ APPLICATION                                                              │
│  control-plane, workflow, pr-loop                                        │
├─────────────────────────────────────────────────────────────────────────┤
│ DOMAIN                                                                   │
│  agent, task, policy, heuristic, artifact                               │
├─────────────────────────────────────────────────────────────────────────┤
│ FOUNDATIONS                                                              │
│  schema, logging, tool                                                   │
├─────────────────────────────────────────────────────────────────────────┤
│ INFRASTRUCTURE                                                           │
│  llm, plugin (implementations)                                           │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Component Definitions

#### Foundations Layer

| Component | Responsibility | BB-Compatible |
|-----------|----------------|---------------|
| `schema` | Malli schemas for all domain types | ✅ |
| `logging` | EDN structured logging, context propagation | ✅ |
| `tool` | Tool protocol, tool registry interface | ✅ |

#### Domain Layer

| Component | Responsibility | BB-Compatible |
|-----------|----------------|---------------|
| `agent` | Agent protocol, role definitions, memory | ✅ |
| `task` | Task schema, task graph, decomposition | ✅ |
| `policy` | Gates, budgets, governance rules | ✅ |
| `heuristic` | Heuristic registry, versioning, A/B | ✅ |
| `artifact` | Artifact schema, provenance, linking | ✅ |

#### Application Layer

| Component | Responsibility | BB-Compatible |
|-----------|----------------|---------------|
| `control-plane` | Orchestration, scheduling, coordination | ✅ |
| `workflow` | Outer loop, inner loop, state machines | ✅ |
| `pr-loop` | PR lifecycle, comment resolution | ✅ |

#### Infrastructure Layer

| Component | Responsibility | BB-Compatible |
|-----------|----------------|---------------|
| `llm` | Claude API client, model selection | ✅ |
| `plugin` | Plugin loader, sandbox, lifecycle | ✅* |

*Plugin sandboxing may require JVM for full isolation

### 3.3 Component Dependencies (DAG)

```clojure
;; Allowed dependencies (interface only)

;; Foundations - depend on nothing internal
schema     -> []
logging    -> [schema]
tool       -> [schema logging]

;; Domain - depend on foundations
agent      -> [schema logging tool]
task       -> [schema logging agent]
policy     -> [schema logging]
heuristic  -> [schema logging]
artifact   -> [schema logging]

;; Application - depend on domain + foundations
control-plane -> [schema logging agent task policy heuristic artifact]
workflow      -> [schema logging agent task policy artifact control-plane]
pr-loop       -> [schema logging agent task artifact workflow]

;; Infrastructure - depend on foundations, implement ports
llm    -> [schema logging]
plugin -> [schema logging tool]

;; Bases - depend on application + infrastructure
cli    -> [control-plane workflow pr-loop llm plugin logging]
server -> [control-plane workflow pr-loop llm plugin logging]
fleet  -> [control-plane workflow pr-loop llm plugin logging]
```

---

## 4. Component Structure

### 4.1 Standard Component Layout

```
components/<name>/
├── deps.edn                    # Component-specific deps
├── src/
│   └── ai/
│       └── miniforge/
│           └── <name>/
│               ├── interface.clj    # Public API (thin, validates)
│               ├── core.clj         # Implementation (layered)
│               ├── spec.clj         # Malli schemas
│               └── <submodule>.clj  # Additional modules
├── test/
│   └── ai/
│       └── miniforge/
│           └── <name>/
│               ├── interface_test.clj
│               └── core_test.clj
└── resources/                  # Component resources (if any)
```

### 4.2 Interface Pattern

```clojure
(ns ai.miniforge.agent.interface
  "Public API for the agent component.
   All external access goes through here."
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.spec :as spec]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Validation helpers

(defn validate-agent [agent]
  (m/validate spec/Agent agent))

;------------------------------------------------------------------------------ Layer 1
;; Public API - delegates to core

(defn create-agent
  "Create a new agent with the given role and configuration."
  [role config]
  {:pre [(validate-agent (merge {:agent/role role} config))]}
  (core/create-agent role config))

(defn execute-task
  "Execute a task with the given agent."
  [agent task context]
  (core/execute-task agent task context))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (create-agent :implementer {:model "claude-sonnet-4"})
  :leave-this-here)
```

### 4.3 Core Pattern (Layered)

```clojure
(ns ai.miniforge.agent.core
  "Agent implementation.
   Layer 0: Pure functions, data transformations
   Layer 1: Stateful operations, I/O coordination
   Layer 2: Orchestration (if needed)"
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0
;; Pure functions - no I/O, no state

(defn build-prompt [agent task context]
  (str (:agent/system-prompt agent) "\n\n"
       "Task: " (:task/description task) "\n\n"
       "Context: " (pr-str context)))

(defn parse-response [response]
  ;; Extract artifacts from LLM response
  ...)

;------------------------------------------------------------------------------ Layer 1
;; I/O operations - calls LLM, logs

(defn execute-task [agent task context]
  (let [prompt   (build-prompt agent task context)
        logger   (log/with-context (:ctx/logger context)
                                   {:agent-id (:agent/id agent)
                                    :task-id (:task/id task)})
        _        (log/info logger :agent/task-started
                           {:agent-role (:agent/role agent)})
        response (llm/complete (:agent/model agent) prompt)
        result   (parse-response response)]
    (log/info logger :agent/task-completed
              {:tokens-used (:tokens response)})
    result))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; REPL testing
  (execute-task sample-agent sample-task sample-context)
  :leave-this-here)
```

---

## 5. Build Targets

### 5.1 Babashka Uberscript (Primary CLI)

Single-file executable for maximum portability:

```clojure
;; build.clj addition

(defn bb-uberscript
  "Build a self-contained Babashka uberscript.
   Bundles all source into a single file."
  [{:keys [project] :or {project "miniforge"}}]
  (let [project-root (ensure-project-root "bb-uberscript" project)
        main-ns      (get-main-ns project)
        cp-roots     (project-classpath-roots project)
        output-file  (str "dist/" project)]
    
    ;; Verify BB compatibility
    (when-not (bb-compatible? main-ns cp-roots)
      (throw (ex-info "Project not BB-compatible" {:project project})))
    
    ;; Generate uberscript
    (fs/create-dirs "dist")
    (bp/shell
     ["bb" "uberscript" output-file
      "--classpath" (str/join ":" cp-roots)
      "--main" (str main-ns)]
     {:dir project-root})
    
    ;; Add shebang
    (let [content (slurp output-file)]
      (spit output-file (str "#!/usr/bin/env bb\n" content)))
    
    (fs/set-posix-file-permissions output-file "rwxr-xr-x")
    (println "✅ Uberscript:" output-file)))
```

### 5.2 JVM Uberjar

For server deployments and JVM-only features:

```clojure
;; Already in build.clj - uberjar function
;; Projects: miniforge-server, miniforge-fleet
```

### 5.3 Build Tasks

```clojure
;; bb.edn additions

{:tasks
 {...existing...

  build:cli
  {:doc  "Build CLI uberscript"
   :task (run! "clojure" "-T:build" "bb-uberscript" ":project" "miniforge")}

  build:cli:jar
  {:doc  "Build CLI as JVM jar (alternative)"
   :task (run! "clojure" "-T:build" "uberjar" ":project" "miniforge")}

  build:server
  {:doc  "Build API server jar"
   :task (run! "clojure" "-T:build" "uberjar" ":project" "miniforge-server")}

  build:fleet
  {:doc  "Build fleet daemon jar"
   :task (run! "clojure" "-T:build" "uberjar" ":project" "miniforge-fleet")}

  build:all
  {:doc     "Build all artifacts"
   :depends [build:cli build:server build:fleet]}

  release
  {:doc  "Build and prepare release artifacts"
   :task (do
           (run-task "build:all")
           (run! "shasum" "-a" "256" "dist/miniforge" ">" "dist/miniforge.sha256")
           (run! "shasum" "-a" "256" "dist/miniforge.jar" ">" "dist/miniforge.jar.sha256"))}}}
```

---

## 6. Distribution

### 6.1 Homebrew Formula

```ruby
# Formula/miniforge.rb

class Miniforge < Formula
  desc "Autonomous SDLC platform - AI-powered software factory"
  homepage "https://miniforge.ai"
  version "0.1.0"
  license "MIT"

  # Babashka is a dependency
  depends_on "babashka"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/miniforge-ai/miniforge/releases/download/v#{version}/miniforge-darwin-arm64"
      sha256 "SHA256_PLACEHOLDER"
    else
      url "https://github.com/miniforge-ai/miniforge/releases/download/v#{version}/miniforge-darwin-amd64"
      sha256 "SHA256_PLACEHOLDER"
    end
  end

  on_linux do
    if Hardware::CPU.arm?
      url "https://github.com/miniforge-ai/miniforge/releases/download/v#{version}/miniforge-linux-arm64"
      sha256 "SHA256_PLACEHOLDER"
    else
      url "https://github.com/miniforge-ai/miniforge/releases/download/v#{version}/miniforge-linux-amd64"
      sha256 "SHA256_PLACEHOLDER"
    end
  end

  def install
    bin.install "miniforge-#{OS.kernel_name.downcase}-#{Hardware::CPU.arch}" => "miniforge"
  end

  def caveats
    <<~EOS
      miniforge requires an Anthropic API key.
      Set it via:
        export ANTHROPIC_API_KEY=your-key-here
      
      Or create a config file at ~/.miniforge/config.edn
    EOS
  end

  test do
    assert_match "miniforge", shell_output("#{bin}/miniforge --version")
  end
end
```

### 6.2 Homebrew Tap Setup

```
homebrew-miniforge/
├── Formula/
│   └── miniforge.rb
└── README.md
```

Installation:
```bash
brew tap miniforge-ai/miniforge
brew install miniforge
```

### 6.3 Release Workflow

```yaml
# .github/workflows/release.yml

name: Release

on:
  push:
    tags:
      - 'v*'

jobs:
  build:
    strategy:
      matrix:
        include:
          - os: macos-latest
            arch: arm64
            target: darwin-arm64
          - os: macos-13
            arch: amd64
            target: darwin-amd64
          - os: ubuntu-latest
            arch: amd64
            target: linux-amd64
          - os: ubuntu-24.04-arm
            arch: arm64
            target: linux-arm64

    runs-on: ${{ matrix.os }}
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Install Babashka
        uses: turtlequeue/setup-babashka@v1
        with:
          babashka-version: '1.3.190'
      
      - name: Install Clojure
        uses: DeLaGuardo/setup-clojure@v1
        with:
          cli: 'latest'
      
      - name: Build uberscript
        run: bb build:cli
      
      - name: Rename artifact
        run: mv dist/miniforge dist/miniforge-${{ matrix.target }}
      
      - name: Generate SHA256
        run: shasum -a 256 dist/miniforge-${{ matrix.target }} > dist/miniforge-${{ matrix.target }}.sha256
      
      - name: Upload artifacts
        uses: actions/upload-artifact@v4
        with:
          name: miniforge-${{ matrix.target }}
          path: dist/miniforge-${{ matrix.target }}*

  release:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - name: Download all artifacts
        uses: actions/download-artifact@v4
      
      - name: Create Release
        uses: softprops/action-gh-release@v1
        with:
          files: |
            miniforge-*/miniforge-*
          generate_release_notes: true

  homebrew:
    needs: release
    runs-on: ubuntu-latest
    steps:
      - name: Update Homebrew formula
        uses: mislav/bump-homebrew-formula-action@v3
        with:
          formula-name: miniforge
          homebrew-tap: miniforge-ai/homebrew-miniforge
        env:
          COMMITTER_TOKEN: ${{ secrets.HOMEBREW_TAP_TOKEN }}
```

### 6.4 Alternative Distribution: curl

```bash
# Install script at https://miniforge.ai/install.sh

#!/bin/bash
set -e

VERSION="${MINIFORGE_VERSION:-latest}"
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

case "$ARCH" in
  x86_64) ARCH="amd64" ;;
  aarch64|arm64) ARCH="arm64" ;;
esac

BINARY="miniforge-${OS}-${ARCH}"
URL="https://github.com/miniforge-ai/miniforge/releases/${VERSION}/download/${BINARY}"

echo "Downloading miniforge..."
curl -fsSL "$URL" -o /tmp/miniforge
chmod +x /tmp/miniforge

INSTALL_DIR="${MINIFORGE_INSTALL_DIR:-/usr/local/bin}"
echo "Installing to $INSTALL_DIR..."
sudo mv /tmp/miniforge "$INSTALL_DIR/miniforge"

echo "✅ miniforge installed successfully!"
miniforge --version
```

Usage:
```bash
curl -fsSL https://miniforge.ai/install.sh | bash
```

---

## 7. Project Configurations

### 7.1 CLI Project

```clojure
;; projects/miniforge/deps.edn

{:paths []
 
 :deps {ai.miniforge/cli             {:local/root "../../bases/cli"}
        ai.miniforge/control-plane   {:local/root "../../components/control-plane"}
        ai.miniforge/workflow        {:local/root "../../components/workflow"}
        ai.miniforge/pr-loop         {:local/root "../../components/pr-loop"}
        ai.miniforge/agent           {:local/root "../../components/agent"}
        ai.miniforge/task            {:local/root "../../components/task"}
        ai.miniforge/policy          {:local/root "../../components/policy"}
        ai.miniforge/heuristic       {:local/root "../../components/heuristic"}
        ai.miniforge/artifact        {:local/root "../../components/artifact"}
        ai.miniforge/schema          {:local/root "../../components/schema"}
        ai.miniforge/logging         {:local/root "../../components/logging"}
        ai.miniforge/tool            {:local/root "../../components/tool"}
        ai.miniforge/llm             {:local/root "../../components/llm"}
        ai.miniforge/plugin          {:local/root "../../components/plugin"}}

 :aliases
 {:uberjar {:main ai.miniforge.cli.main}
  
  :test {:extra-paths ["test"]
         :extra-deps {lambdaisland/kaocha {:mvn/version "1.91.1392"}}}}}
```

### 7.2 Server Project (JVM-only features)

```clojure
;; projects/miniforge-server/deps.edn

{:paths ["src" "resources"]
 
 :deps {;; All CLI deps plus...
        ai.miniforge/server {:local/root "../../bases/server"}
        
        ;; JVM-only deps
        ring/ring-core {:mvn/version "1.12.1"}
        ring/ring-jetty-adapter {:mvn/version "1.12.1"}
        metosin/reitit {:mvn/version "0.7.0"}}

 :aliases
 {:uberjar {:main ai.miniforge.server.main}}}
```

### 7.3 Fleet Project

```clojure
;; projects/miniforge-fleet/deps.edn

{:paths ["src" "resources"]
 
 :deps {ai.miniforge/fleet {:local/root "../../bases/fleet"}
        ;; Same as server plus fleet-specific deps
        }

 :aliases
 {:uberjar {:main ai.miniforge.fleet.main}}}
```

---

## 8. Base Entry Points

### 8.1 CLI Base

```clojure
;; bases/cli/src/ai/miniforge/cli/main.clj

(ns ai.miniforge.cli.main
  "miniforge CLI entry point.
   BB-compatible - no JVM-only deps."
  (:require
   [ai.miniforge.cli.commands :as commands]
   [ai.miniforge.cli.parser :as parser]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

(def version "0.1.0")

(def commands
  {:run       commands/run
   :step      commands/step
   :chain     commands/chain
   :pr        commands/pr
   :fleet     commands/fleet
   :operator  commands/operator
   :version   (fn [_] (println "miniforge" version))
   :help      commands/help})

;------------------------------------------------------------------------------ Layer 1

(defn -main [& args]
  (let [{:keys [command options errors]} (parser/parse args)]
    (cond
      errors
      (do (doseq [e errors] (println "Error:" e))
          (System/exit 1))
      
      (contains? commands command)
      ((get commands command) options)
      
      :else
      (do (commands/help {})
          (System/exit 1)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (-main "run" "./spec.md")
  (-main "step" "plan" "--spec" "./spec.md")
  (-main "fleet" "dashboard")
  :leave-this-here)
```

### 8.2 Server Base (JVM-only)

```clojure
;; bases/server/src/ai/miniforge/server/main.clj

(ns ai.miniforge.server.main
  "miniforge API server.
   JVM-only - uses Ring/Jetty."
  (:require
   [ai.miniforge.server.routes :as routes]
   [ai.miniforge.server.middleware :as mw]
   [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn -main [& args]
  (let [port (or (some-> (first args) parse-long) 8080)]
    (println "Starting miniforge server on port" port)
    (jetty/run-jetty (mw/wrap-all routes/handler)
                     {:port port :join? true})))
```

---

## 9. BB Compatibility Requirements

### 9.1 Allowed Dependencies

```clojure
;; BB-compatible deps (use freely)
{org.clojure/clojure        {:mvn/version "1.11.1"}
 metosin/malli              {:mvn/version "0.16.1"}
 com.cognitect/transit-clj  {:mvn/version "1.0.333"}
 org.clojure/core.async     {:mvn/version "1.6.681"}
 babashka/fs                {:mvn/version "0.5.20"}
 babashka/process           {:mvn/version "0.5.22"}
 org.babashka/http-client   {:mvn/version "0.4.19"}
 org.babashka/cli           {:mvn/version "0.8.60"}}
```

### 9.2 JVM-Only (Bases: server, fleet)

```clojure
;; JVM-only deps (only in server/fleet projects)
{ring/ring-core             {:mvn/version "1.12.1"}
 ring/ring-jetty-adapter    {:mvn/version "1.12.1"}
 com.github.seancorfield/next.jdbc {:mvn/version "1.3.909"}}
```

### 9.3 Compatibility Check

Build fails if JVM-only code leaks into BB-compatible components:

```clojure
;; build.clj - bb-compatible? function already does this
(when-not (bb-compatible? main-ns cp-roots)
  (throw (ex-info "Not BB-compatible" {:project project})))
```

---

## 10. Configuration

### 10.1 User Configuration

```clojure
;; ~/.miniforge/config.edn

{:anthropic/api-key "sk-ant-..."  ;; or use ANTHROPIC_API_KEY env
 
 :defaults
 {:model "claude-sonnet-4"
  :max-tokens 8000
  :budget {:tokens-per-task 50000
           :cost-per-workflow 10.00}}
 
 :heuristics-dir "~/.miniforge/heuristics"
 :plugins-dir "~/.miniforge/plugins"
 
 :fleet
 {:repos []
  :concurrency 3}}
```

### 10.2 Project Configuration

```clojure
;; .miniforge/config.edn (in repo root)

{:project/name "my-project"
 
 :agents
 {:implementer {:model "claude-sonnet-4"
                :context-files ["CONTRIBUTING.md" "STYLE.md"]}
  :reviewer {:model "claude-opus-4"}}
 
 :policies
 {:require-tests true
  :require-review true
  :max-pr-size 500}
 
 :plugins [:shipyard :github]}
```

---

## 11. Deliverables

### Phase 0: Bootstrap

- [ ] Create component skeletons with interface.clj stubs
- [ ] Set up project deps.edn files
- [ ] Verify BB uberscript build works
- [ ] Set up GitHub Actions for CI

### Phase 1: Core Components

- [ ] `schema` - All Malli schemas from specs
- [ ] `logging` - EDN logger with context
- [ ] `llm` - Claude API client

### Phase 2: Domain

- [ ] `agent` - Agent execution
- [ ] `task` - Task management
- [ ] `policy` - Gates and budgets

### Phase 3: Application

- [ ] `workflow` - Inner/outer loops
- [ ] `control-plane` - Orchestration
- [ ] `pr-loop` - PR management

### Phase 4: CLI

- [ ] `cli` base - Command parser
- [ ] Single-step commands
- [ ] Fleet mode TUI

### Phase 5: Distribution

- [ ] GitHub Release workflow
- [ ] Homebrew tap
- [ ] Install script

---

## 12. Development Workflow

```bash
# Start REPL
clj -A:dev

# Run CLI in dev mode
bb -f dist/development miniforge run ./spec.md

# Run tests
bb test

# Build CLI
bb build:cli

# Test built CLI
./dist/miniforge --version

# Full pre-commit
bb pre-commit
```

---

## 13. Open Questions

1. **GraalVM native-image**: Worth pursuing for even faster startup?
2. **Windows support**: BB works on Windows, but shell scripts don't
3. **Plugin isolation**: BB can't do true sandboxing—acceptable for v1?
4. **Homebrew vs Nix**: Should we support Nix flakes too?
5. **Auto-update**: Should CLI self-update? How?
