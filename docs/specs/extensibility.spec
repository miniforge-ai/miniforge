# miniforge.ai — Extensibility Specification

**Version:** 0.1.0  
**Status:** Draft  
**Date:** 2026-01-18  

---

## 1. Overview

### 1.1 Purpose

miniforge.ai extends agent capabilities through a **plugin architecture** that provides:

- Additional tools agents can invoke during task execution
- New artifact types and validators
- External service integrations (Shipyard, cloud providers, etc.)
- Custom gates and policies

Plugins are the mechanism for customer-specific and domain-specific customization without modifying core.

### 1.2 Design Principles

1. **Agents don't know plugins**: Agents discover capabilities at runtime via the tool registry
2. **Plugins are pure adapters**: They implement ports; they don't modify domain logic
3. **Sandboxed execution**: Plugins run with explicit capability grants
4. **Hot-loadable**: Plugins can be added/removed without system restart
5. **Versioned contracts**: Plugin APIs are versioned; breaking changes require major version bump

---

## 2. Plugin Architecture

### 2.1 Stratum Placement

```
┌─────────────────────────────────────────────────────────┐
│ ADAPTERS: CLI, API, Webhooks                            │
├─────────────────────────────────────────────────────────┤
│ APPLICATION: Orchestrator, Plugin Manager               │
├─────────────────────────────────────────────────────────┤
│ DOMAIN: Agents, Loops (uses Tool Registry)              │
├─────────────────────────────────────────────────────────┤
│ FOUNDATIONS: Tool Protocol, Plugin Schema               │
└─────────────────────────────────────────────────────────┘
         ▼                              ▲
┌─────────────────────────────────────────────────────────┐
│ INFRASTRUCTURE: Plugin Runtime, Sandboxing              │
├─────────────────────────────────────────────────────────┤
│ PLUGINS: Shipyard, GitHub, AWS, Custom Tools            │
└─────────────────────────────────────────────────────────┘
```

### 2.2 Core Components

| Component        | Stratum       | Responsibility                              |
|------------------|---------------|---------------------------------------------|
| Tool Protocol    | Foundations   | Interface all tools must implement          |
| Tool Registry    | Domain        | Runtime discovery of available tools        |
| Plugin Manager   | Application   | Load, validate, lifecycle management        |
| Plugin Runtime   | Infrastructure| Sandboxed execution environment             |
| Capability Grants| Domain/Policy | What each plugin is allowed to do           |

---

## 3. Tool Protocol

### 3.1 Tool Definition Schema

```clojure
{:tool/id           keyword         ; unique identifier, e.g. :shipyard/create-env
 :tool/name         string          ; human-readable name
 :tool/description  string          ; what the tool does (for agent context)
 :tool/version      string          ; semver
 
 ;; Input/Output contracts
 :tool/parameters   [:map ...]      ; Malli schema for inputs
 :tool/returns      [:map ...]      ; Malli schema for outputs
 
 ;; Execution characteristics
 :tool/async?       boolean         ; does this return immediately or block?
 :tool/idempotent?  boolean         ; safe to retry?
 :tool/side-effects #{keyword}      ; :network, :filesystem, :external-service, etc.
 
 ;; Cost/risk metadata
 :tool/cost-model   map             ; estimated cost per invocation
 :tool/risk-level   keyword         ; :low, :medium, :high, :critical
 :tool/timeout-ms   long            ; max execution time
 
 ;; Capabilities required
 :tool/requires     #{keyword}}     ; capability grants needed
```

### 3.2 Tool Protocol

```clojure
(defprotocol Tool
  (tool-id [this]
    "Return the tool's unique identifier")
  
  (tool-spec [this]
    "Return the full tool definition schema")
  
  (invoke [this params context]
    "Execute the tool with given parameters and execution context.
     Returns {:result ... :logs [...] :cost {...}} or throws")
  
  (validate-params [this params]
    "Validate parameters against schema, returns errors or nil")
  
  (estimate-cost [this params]
    "Estimate execution cost without running"))
```

### 3.3 Execution Context

Passed to every tool invocation:

```clojure
{:ctx/workflow-id    uuid
 :ctx/task-id        uuid
 :ctx/agent-id       uuid
 :ctx/capabilities   #{keyword}      ; granted capabilities
 :ctx/budget         {:tokens n :cost-usd n :time-ms n}
 :ctx/logger         Logger          ; for structured logging
 :ctx/secrets        SecretProvider  ; access to credentials
 :ctx/artifacts      ArtifactStore}  ; access to artifact store
```

---

## 4. Plugin Schema

### 4.1 Plugin Manifest

Each plugin declares itself via a manifest:

```clojure
{:plugin/id          keyword         ; unique identifier, e.g. :shipyard
 :plugin/name        string          ; human-readable name
 :plugin/version     string          ; semver
 :plugin/description string
 :plugin/author      string
 :plugin/license     string
 
 ;; What this plugin provides
 :plugin/tools       [Tool]          ; list of tools provided
 :plugin/artifacts   [ArtifactType]  ; new artifact types (optional)
 :plugin/validators  [Validator]     ; new validators (optional)
 :plugin/gates       [Gate]          ; new policy gates (optional)
 
 ;; What this plugin requires
 :plugin/capabilities #{keyword}     ; capabilities this plugin needs
 :plugin/dependencies [PluginDep]    ; other plugins required
 :plugin/config-schema [:map ...]    ; configuration schema
 
 ;; Lifecycle hooks
 :plugin/on-load     fn              ; called when plugin loads
 :plugin/on-unload   fn              ; called before unload
 :plugin/health-check fn}            ; periodic health verification
```

### 4.2 Capability Grants

Plugins request capabilities; administrators grant them:

| Capability            | Allows                                        | Risk    |
|-----------------------|-----------------------------------------------|---------|
| `:network/outbound`   | Make HTTP requests to external services       | Medium  |
| `:network/inbound`    | Receive webhooks/callbacks                    | Medium  |
| `:filesystem/read`    | Read from local filesystem                    | Low     |
| `:filesystem/write`   | Write to local filesystem                     | Medium  |
| `:secrets/read`       | Access stored credentials                     | High    |
| `:artifacts/read`     | Read artifacts from store                     | Low     |
| `:artifacts/write`    | Write artifacts to store                      | Medium  |
| `:subprocess/spawn`   | Execute external processes                    | High    |
| `:cloud/aws`          | Use AWS SDK                                   | High    |
| `:cloud/gcp`          | Use GCP SDK                                   | High    |
| `:kubernetes/read`    | Read K8s resources                            | Medium  |
| `:kubernetes/write`   | Create/modify K8s resources                   | Critical|

### 4.3 Plugin Configuration

```clojure
;; In control plane config
{:plugins
 {:shipyard {:enabled true
             :version "1.2.0"
             :capabilities #{:network/outbound :secrets/read}
             :config {:api-endpoint "https://api.shipyard.build"
                      :org-id "acme-corp"}
             :cost-limits {:per-invocation 5.00
                           :per-hour 100.00}}
  
  :github   {:enabled true
             :capabilities #{:network/outbound :secrets/read}
             :config {:org "acme-corp"}}}}
```

---

## 5. Tool Registry

### 5.1 Registry Interface

```clojure
(defprotocol ToolRegistry
  (register-tool [this tool]
    "Register a tool, making it available to agents")
  
  (unregister-tool [this tool-id]
    "Remove a tool from the registry")
  
  (list-tools [this]
    "List all registered tools")
  
  (list-tools-for-agent [this agent-role capabilities]
    "List tools available to a specific agent given its role and granted capabilities")
  
  (get-tool [this tool-id]
    "Retrieve a specific tool by ID")
  
  (describe-tools [this tool-ids]
    "Generate tool descriptions for agent context (prompt injection)"))
```

### 5.2 Agent Tool Discovery

Agents receive tool descriptions in their context:

```clojure
;; Injected into agent prompt context
{:available-tools
 [{:id :shipyard/create-env
   :description "Create an ephemeral environment for testing"
   :parameters {:name "string, required"
                :base-image "string, required"
                :ttl-hours "integer, default 4"}
   :returns {:env-id "string"
             :url "string"
             :status "string"}}
  
  {:id :github/create-pr
   :description "Create a pull request"
   :parameters {:repo "string" :branch "string" :title "string" :body "string"}
   :returns {:pr-number "integer" :url "string"}}
  ...]}
```

---

## 6. Plugin Manager

### 6.1 Manager Interface

```clojure
(defprotocol PluginManager
  (load-plugin [this plugin-path config]
    "Load and initialize a plugin")
  
  (unload-plugin [this plugin-id]
    "Gracefully unload a plugin")
  
  (reload-plugin [this plugin-id]
    "Hot-reload a plugin (unload + load)")
  
  (list-plugins [this]
    "List all loaded plugins with status")
  
  (get-plugin-status [this plugin-id]
    "Get detailed status for a plugin")
  
  (validate-plugin [this plugin-manifest]
    "Validate a plugin manifest before loading")
  
  (check-compatibility [this plugin-manifest]
    "Check if plugin is compatible with current system version"))
```

### 6.2 Plugin Lifecycle

```
                    ┌──────────────┐
                    │  DISCOVERED  │
                    └──────┬───────┘
                           │ validate
                           ▼
                    ┌──────────────┐
              ┌───► │   VALIDATED  │ ◄───┐
              │     └──────┬───────┘     │
              │            │ load        │
              │            ▼             │
              │     ┌──────────────┐     │
              │     │   LOADING    │     │
              │     └──────┬───────┘     │
              │            │ on-load     │
              │            ▼             │
              │     ┌──────────────┐     │ reload
              │     │    ACTIVE    │ ────┘
              │     └──────┬───────┘
              │            │ unload
              │            ▼
              │     ┌──────────────┐
              │     │  UNLOADING   │
              │     └──────┬───────┘
              │            │ on-unload
              │            ▼
              │     ┌──────────────┐
              └──── │   INACTIVE   │
                    └──────────────┘
```

### 6.3 Error Handling

| Failure Mode           | Response                                    |
|------------------------|---------------------------------------------|
| Load failure           | Log error, plugin stays INACTIVE            |
| Health check failure   | Retry N times, then mark DEGRADED           |
| Invocation timeout     | Return error, log, increment failure counter|
| Repeated failures      | Circuit-break: disable tool temporarily     |
| Capability violation   | Block invocation, log security event        |

---

## 7. Example Plugin: Shipyard

### 7.1 Plugin Manifest

```clojure
{:plugin/id          :shipyard
 :plugin/name        "Shipyard Ephemeral Environments"
 :plugin/version     "1.0.0"
 :plugin/description "Create and manage ephemeral environments for testing"
 :plugin/author      "miniforge.ai"
 
 :plugin/tools
 [{:tool/id          :shipyard/create-env
   :tool/name        "Create Environment"
   :tool/description "Spin up an ephemeral environment with the current code"
   :tool/parameters  [:map
                      [:name :string]
                      [:base-image :string]
                      [:ttl-hours {:default 4} :int]
                      [:services {:optional true} [:vector :string]]]
   :tool/returns     [:map
                      [:env-id :string]
                      [:url :string]
                      [:status [:enum :creating :ready :failed]]
                      [:expires-at :inst]]
   :tool/async?      true
   :tool/idempotent? false
   :tool/side-effects #{:external-service}
   :tool/cost-model  {:base 0.50 :per-hour 0.10}
   :tool/risk-level  :medium
   :tool/timeout-ms  300000
   :tool/requires    #{:network/outbound :secrets/read}}
  
  {:tool/id          :shipyard/destroy-env
   :tool/name        "Destroy Environment"
   :tool/description "Tear down an ephemeral environment"
   :tool/parameters  [:map [:env-id :string]]
   :tool/returns     [:map [:status [:enum :destroyed :not-found]]]
   :tool/async?      false
   :tool/idempotent? true
   :tool/side-effects #{:external-service}
   :tool/cost-model  {:base 0}
   :tool/risk-level  :low
   :tool/timeout-ms  60000
   :tool/requires    #{:network/outbound :secrets/read}}
  
  {:tool/id          :shipyard/get-env-status
   :tool/name        "Get Environment Status"
   :tool/description "Check status and health of an ephemeral environment"
   :tool/parameters  [:map [:env-id :string]]
   :tool/returns     [:map
                      [:status [:enum :creating :ready :failed :expired]]
                      [:url {:optional true} :string]
                      [:health {:optional true} [:map [:http :boolean] [:services :map]]]]
   :tool/async?      false
   :tool/idempotent? true
   :tool/side-effects #{}
   :tool/cost-model  {:base 0}
   :tool/risk-level  :low
   :tool/timeout-ms  10000
   :tool/requires    #{:network/outbound :secrets/read}}
  
  {:tool/id          :shipyard/run-tests
   :tool/name        "Run Tests in Environment"
   :tool/description "Execute test suite against an ephemeral environment"
   :tool/parameters  [:map
                      [:env-id :string]
                      [:test-command :string]
                      [:timeout-minutes {:default 10} :int]]
   :tool/returns     [:map
                      [:status [:enum :passed :failed :timeout :error]]
                      [:output :string]
                      [:exit-code :int]
                      [:duration-ms :int]]
   :tool/async?      true
   :tool/idempotent? true
   :tool/side-effects #{:external-service}
   :tool/cost-model  {:base 0.10 :per-minute 0.05}
   :tool/risk-level  :medium
   :tool/timeout-ms  900000
   :tool/requires    #{:network/outbound :secrets/read}}]
 
 :plugin/capabilities #{:network/outbound :secrets/read}
 :plugin/config-schema [:map
                        [:api-endpoint :string]
                        [:org-id :string]
                        [:default-ttl-hours {:default 4} :int]]}
```

### 7.2 Use Case: Dev-Test Loop with Live Environments

```
Agent: Implementer
  │
  ▼ produces :code artifact
  │
Agent: Tester
  │
  ├─► invoke :shipyard/create-env
  │     └─► returns {:env-id "env-123" :url "https://env-123.shipyard.build"}
  │
  ├─► invoke :shipyard/run-tests {:env-id "env-123" :test-command "npm test"}
  │     └─► returns {:status :passed :output "..." :duration-ms 45000}
  │
  ├─► (on failure) generate repair, loop
  │
  └─► invoke :shipyard/destroy-env {:env-id "env-123"}
```

---

## 8. Sandboxing

### 8.1 Execution Boundaries

Plugins run in isolated execution contexts:

- **Network**: Only allowed endpoints (allowlist per plugin)
- **Filesystem**: Only designated directories (workspace, temp)
- **Secrets**: Only explicitly granted secret paths
- **Time**: Enforced timeouts per invocation
- **Resources**: Memory and CPU limits

### 8.2 Sandbox Configuration

```clojure
{:sandbox/network
 {:allow-list ["api.shipyard.build"
               "api.github.com"]
  :deny-list  ["*.internal" "localhost"]}
 
 :sandbox/filesystem
 {:read-paths  ["/workspace" "/tmp/miniforge"]
  :write-paths ["/tmp/miniforge"]}
 
 :sandbox/secrets
 {:allowed-paths ["shipyard/*" "github/token"]}
 
 :sandbox/resources
 {:max-memory-mb 512
  :max-cpu-seconds 60}}
```

---

## 9. Deliverables

### Phase 0 (Foundations)

- [ ] Tool protocol definition
- [ ] Plugin manifest schema
- [ ] Capability grant schema

### Phase 1 (Domain)

- [ ] Tool registry implementation
- [ ] Tool invocation context

### Phase 2 (Application)

- [ ] Plugin manager
- [ ] Plugin lifecycle handling
- [ ] Hot-reload mechanism

### Phase 3 (Infrastructure)

- [ ] Sandbox runtime
- [ ] Plugin loader (filesystem-based)
- [ ] Health check scheduler

### Phase 4 (Plugins)

- [ ] Shipyard plugin
- [ ] GitHub plugin
- [ ] AWS plugin (S3, Lambda, etc.)

---

## 10. Open Questions

1. **Plugin distribution**: Registry/marketplace vs manual installation?
2. **Plugin language**: Clojure-only or polyglot (via gRPC/HTTP)?
3. **Plugin secrets**: Per-plugin credential namespacing?
4. **Plugin updates**: Auto-update vs manual approval?
5. **Plugin marketplace**: Curated vs open ecosystem?
