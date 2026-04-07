# Tool/LSP Registry System

**Status:** Implemented (Phases 1-3)
**Component:** `tool-registry`

**Goal:** Extensible tool registry that agents can use, with LSP servers as a first-class
tool type. Simple to add (drop a file), manageable via fleet dashboard.

---

## Architecture

```text
ADAPTERS:    CLI commands, Dashboard panels
APPLICATION: Tool Orchestrator, LSP Manager
DOMAIN:      Tool Registry (extends existing tool component)
FOUNDATIONS: Tool Schema (Malli)
INFRA:       File Loader, Process Manager, File Watcher
```

New component: `tool-registry` (separate from existing `tool` protocol component)

---

## Tool Configuration Schema

### Base Tool (EDN)

```clojure
{:tool/id          :lsp/clojure        ; Namespaced keyword
 :tool/type        :lsp                ; :function, :lsp, :mcp, :external
 :tool/name        "Clojure LSP"
 :tool/description "Language server for Clojure"
 :tool/version     "1.0.0"
 :tool/config      {...}               ; Type-specific config
 :tool/capabilities #{:code/diagnostics :code/format}
 :tool/requires    #{:subprocess/spawn}
 :tool/enabled     true
 :tool/tags        #{:language-server :clojure}}
```

### LSP Config

```clojure
:tool/config
{:lsp/command        ["clojure-lsp"]
 :lsp/languages      #{"clojure" "clojurescript"}
 :lsp/file-patterns  ["**/*.clj" "**/*.cljs"]
 :lsp/capabilities   #{:diagnostics :format :hover}
 :lsp/start-mode     :on-demand        ; or :eager
 :lsp/shutdown-after-ms 300000}        ; 5 min idle timeout
```

---

## File Locations

```text
~/.miniforge/tools/           # User-installed tools
├── lsp/
│   ├── clojure.edn
│   ├── typescript.edn
│   └── python.edn
├── mcp/
│   └── github.edn
└── custom/
    └── my-tool.edn

components/tool-registry/resources/tools/   # Built-in tools
└── lsp/
    └── clojure.edn
```

**Discovery order** (later overrides earlier):

1. Built-in: `resources/tools/**/*.edn`
2. User: `~/.miniforge/tools/**/*.edn`
3. Project: `.miniforge/tools/**/*.edn`

---

## Component Structure

```text
components/tool-registry/
├── src/ai/miniforge/tool_registry/
│   ├── interface.clj          # Public API
│   ├── schema.clj             # Malli schemas + result helpers
│   ├── loader.clj             # EDN file discovery/loading
│   ├── registry.clj           # In-memory registry
│   └── lsp/
│       ├── protocol.clj       # LSP JSON-RPC types
│       ├── process.clj        # Process lifecycle
│       ├── client.clj         # LSP client
│       └── manager.clj        # Server orchestration (pipeline pattern)
├── resources/tools/           # Built-in tool configs
│   └── lsp/
│       └── clojure.edn
└── test/                      # Unit + integration tests
```

---

## Key Functions

### interface.clj

```clojure
;; Registry
(create-registry opts)         ; :auto-load? :watch? :logger
(load-tools)                   ; Load from standard locations
(register! registry tool)
(list-tools registry)
(find-tools registry query)

;; LSP
(start-lsp registry tool-id)
(stop-lsp registry tool-id)
(lsp-status registry tool-id)  ; :stopped :starting :running :error

;; For agents
(tools-for-context registry capabilities)

;; Dashboard
(tools-with-status registry)
```

### LSP Manager Pipeline

```clojure
;; start-server uses pipeline pattern:
(-> initial-state
    step-validate-tool      ; Check tool exists, is LSP, is enabled
    step-check-existing     ; Handle already-running or clean up dead process
    step-start-process      ; Spawn the LSP server process
    step-create-client      ; Create LSP client from process
    step-initialize         ; Send LSP initialize request
    step-register           ; Store server in registry
    pipeline->result)
```

### Result Helpers (schema.clj)

```clojure
(ok)                        ;; => {:success? true}
(ok :client lsp-client)     ;; => {:success? true :client lsp-client}
(err "Something failed")    ;; => {:success? false :error "Something failed"}
(err "Tool not found: " id) ;; => {:success? false :error "Tool not found: :lsp/foo"}
```

---

## Dashboard Integration (Future)

### Web Panel (add to cli/web.clj)

```clojure
[:div.tools-panel
 [:div.tools-header "Tools" [:button "Reload"]]
 [:div.tools-list
  (for [{:tool/keys [id name type]} tools]
    [:div.tool-item
     [:span.tool-icon (type-icon type)]
     [:span.tool-name name]
     [:span.tool-status status]
     (when (= type :lsp)
       [:button (if running? "Stop" "Start")])])]]
```

### API Endpoints

- `GET /api/tools` - List all tools with status
- `GET /api/tools/:id` - Tool detail
- `POST /api/tools/:id/start` - Start LSP
- `POST /api/tools/:id/stop` - Stop LSP
- `POST /api/tools/reload` - Reload configs from disk

### CLI Commands

```bash
miniforge tools list
miniforge tools info <id>
miniforge tools start <id>
miniforge tools stop <id>
miniforge tools reload
```

---

## Agent Integration (Future)

Extend `workflow/context.clj`:

```clojure
(defn create-context [workflow input opts]
  (let [tool-registry (or (:tool-registry opts)
                          (tool-registry/create-registry {}))]
    (merge existing-context
           {:tool-registry tool-registry
            :available-tools (list-tools tool-registry)})))
```

Agents discover tools via context, filtered by capabilities and role.

---

## Implementation Phases

### Phase 1: Foundation [DONE]

- [x] Create `tool-registry` component skeleton
- [x] `schema.clj` - Malli schemas for tool configs
- [x] `loader.clj` - EDN file discovery and loading
- [x] Unit tests

### Phase 2: Registry Core [DONE]

- [x] `registry.clj` - In-memory registry with CRUD
- [x] Function tool instantiation
- [x] `interface.clj` - Public API

### Phase 3: LSP Support [DONE]

- [x] `lsp/protocol.clj` - JSON-RPC message types
- [x] `lsp/process.clj` - Process start/stop/health
- [x] `lsp/client.clj` - LSP client communication
- [x] `lsp/manager.clj` - Lifecycle orchestration (pipeline pattern)
- [x] Built-in `clojure.edn` config
- [x] E2E integration tests with clojure-lsp

### Phase 4: Dashboard [TODO]

- [ ] Web panel in `cli/web.clj`
- [ ] TUI panel in `cli/tui.clj`
- [ ] API endpoints
- [ ] CLI commands

### Phase 5: File Watching [TODO]

- [ ] `watcher.clj` - Java WatchService integration
- [ ] Hot-reload on config changes

### Phase 6: Agent Integration [TODO]

- [ ] Extend workflow context
- [ ] Tool discovery helpers for agents

---

## Verification

```bash
# Unit tests
clj -M:dev:test -e "(require '[clojure.test :as t] '[ai.miniforge.tool-registry.schema-test]) (t/run-tests 'ai.miniforge.tool-registry.schema-test)"
clj -M:dev:test -e "(require '[clojure.test :as t] '[ai.miniforge.tool-registry.loader-test]) (t/run-tests 'ai.miniforge.tool-registry.loader-test)"

# Integration (requires clojure-lsp)
clj -M:dev:test -e "(require '[clojure.test :as t] '[ai.miniforge.tool-registry.integration-test]) (t/run-tests 'ai.miniforge.tool-registry.integration-test)"

# Conformance
clj -M:dev:test:conformance -e "(require '[clojure.test :as t] '[conformance.tool-registry-conformance-test]) (t/run-tests 'conformance.tool-registry-conformance-test)"
```
