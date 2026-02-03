;; Copyright 2025 miniforge.ai
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.tool-registry.interface
  "Public API for the tool-registry component.

   Provides an extensible tool registry that agents can use, with LSP
   servers as a first-class tool type. Tools are defined via EDN files
   that can be dropped into standard locations for easy management.

   Layer structure:
   Layer 0: Schema re-exports and type definitions
   Layer 1: Registry creation and tool management
   Layer 2: LSP server lifecycle management
   Layer 3: Tool discovery and loading
   Layer 4: Dashboard integration helpers

   File locations (discovery order, later overrides earlier):
   1. Built-in: resources/tools/**/*.edn
   2. User: ~/.miniforge/tools/**/*.edn
   3. Project: .miniforge/tools/**/*.edn

   Tool types supported:
   - :function - Simple function-based tools
   - :lsp - Language Server Protocol servers
   - :mcp - Model Context Protocol servers (future)
   - :external - External process tools (future)

   Example:
     ;; Create a registry with auto-loading
     (def registry (create-registry {:auto-load? true}))

     ;; List available tools
     (list-tools registry)

     ;; Start an LSP server
     (start-lsp registry :lsp/clojure)

     ;; Get tools for a specific context
     (tools-for-context registry #{:code/diagnostics})

   See docs/specs/extensibility.spec for full specification."
  (:require
   [ai.miniforge.tool-registry.schema :as schema]
   [ai.miniforge.tool-registry.registry :as registry]
   [ai.miniforge.tool-registry.loader :as loader]
   [ai.miniforge.tool-registry.lsp.manager :as lsp-manager]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def ToolConfig
  "Malli schema for tool configuration."
  schema/ToolConfig)

(def LSPConfig
  "Malli schema for LSP-specific configuration."
  schema/LSPConfig)

(def tool-types
  "Set of supported tool types."
  schema/tool-types)

(def lsp-capabilities
  "Set of supported LSP capabilities."
  schema/lsp-capabilities)

;------------------------------------------------------------------------------ Layer 1
;; Registry creation and management

(defn create-registry
  "Create a new tool registry.

   Options:
   - :auto-load?  - Load tools from standard locations on creation (default: false)
   - :watch?      - Watch for file changes and hot-reload (default: false)
   - :logger      - Logger instance for events
   - :project-dir - Project directory for .miniforge/tools/ lookup

   Returns a ToolRegistry instance.

   Example:
     (create-registry)
     (create-registry {:auto-load? true :watch? true})"
  ([] (create-registry {}))
  ([opts] (registry/create-registry opts)))

(defn register!
  "Register a tool in the registry.

   Arguments:
   - registry - ToolRegistry instance
   - tool     - Tool configuration map

   Returns the tool ID on success.
   Throws if tool config is invalid.

   Example:
     (register! registry {:tool/id :lsp/python
                          :tool/type :lsp
                          :tool/name \"Python LSP\"
                          ...})"
  [registry tool]
  (registry/register-tool registry tool))

(defn unregister!
  "Remove a tool from the registry.

   Arguments:
   - registry - ToolRegistry instance
   - tool-id  - Tool identifier keyword

   Returns the tool ID."
  [registry tool-id]
  (registry/unregister-tool registry tool-id))

(defn get-tool
  "Retrieve a tool by ID.

   Returns the tool configuration map or nil if not found."
  [registry tool-id]
  (registry/get-tool registry tool-id))

(defn list-tools
  "List all registered tools.

   Returns a sequence of tool configuration maps."
  [registry]
  (registry/list-tools registry))

(defn find-tools
  "Find tools matching a query.

   Arguments:
   - registry - ToolRegistry instance
   - query    - Map with optional keys:
                :type - Tool type (:function, :lsp, :mcp, :external)
                :capabilities - Set of required capabilities
                :tags - Set of tags to match
                :enabled - Boolean filter for enabled status
                :text - Text search in name/description

   Returns sequence of matching tools.

   Example:
     (find-tools registry {:type :lsp})
     (find-tools registry {:capabilities #{:code/diagnostics}})
     (find-tools registry {:tags #{:clojure} :enabled true})"
  [registry query]
  (registry/find-tools registry query))

(defn update-tool!
  "Update a tool's configuration.

   Arguments:
   - registry - ToolRegistry instance
   - tool-id  - Tool identifier
   - updates  - Map of updates to merge

   Returns updated tool configuration."
  [registry tool-id updates]
  (registry/update-tool registry tool-id updates))

(defn enable-tool!
  "Enable a tool."
  [registry tool-id]
  (registry/update-tool registry tool-id {:tool/enabled true}))

(defn disable-tool!
  "Disable a tool."
  [registry tool-id]
  (registry/update-tool registry tool-id {:tool/enabled false}))

;------------------------------------------------------------------------------ Layer 2
;; LSP server lifecycle

(defn start-lsp
  "Start an LSP server for a tool.

   Arguments:
   - registry - ToolRegistry instance
   - tool-id  - LSP tool identifier

   Returns:
   - {:success? true :client LSPClient} on success
   - {:success? false :error string} on failure

   Example:
     (start-lsp registry :lsp/clojure)"
  [registry tool-id]
  (lsp-manager/start-server registry tool-id))

(defn stop-lsp
  "Stop an LSP server.

   Arguments:
   - registry - ToolRegistry instance
   - tool-id  - LSP tool identifier

   Returns:
   - {:success? true} on success
   - {:success? false :error string} on failure"
  [registry tool-id]
  (lsp-manager/stop-server registry tool-id))

(defn lsp-status
  "Get the status of an LSP server.

   Returns one of:
   - :stopped  - Server is not running
   - :starting - Server is starting up
   - :running  - Server is running and healthy
   - :error    - Server encountered an error"
  [registry tool-id]
  (lsp-manager/server-status registry tool-id))

(defn get-lsp-client
  "Get the LSP client for a tool, starting the server if needed.

   Arguments:
   - registry - ToolRegistry instance
   - tool-id  - LSP tool identifier

   Returns LSP client or nil if unavailable."
  [registry tool-id]
  (lsp-manager/get-client registry tool-id))

(defn list-lsp-servers
  "List all LSP servers with their status.

   Returns sequence of {:tool-id :status :uptime-ms}."
  [registry]
  (lsp-manager/list-servers registry))

;------------------------------------------------------------------------------ Layer 3
;; Tool discovery and loading

(defn load-tools
  "Load tools from standard locations into the registry.

   Discovery order (later overrides earlier):
   1. Built-in: resources/tools/**/*.edn
   2. User: ~/.miniforge/tools/**/*.edn
   3. Project: .miniforge/tools/**/*.edn (if :project-dir set)

   Arguments:
   - registry - ToolRegistry instance

   Returns:
   - {:loaded [tool-ids...] :failed [{:path :error}...]}

   Example:
     (load-tools registry)"
  [registry]
  (loader/load-all-tools registry))

(defn load-tool-file
  "Load a single tool configuration file.

   Arguments:
   - registry - ToolRegistry instance
   - path     - Path to .edn file

   Returns:
   - {:success? true :tool-id keyword} on success
   - {:success? false :error string} on failure"
  [registry path]
  (loader/load-tool-file registry path))

(defn discover-tools
  "Discover tool files without loading them.

   Arguments:
   - registry - ToolRegistry instance

   Returns sequence of {:path :type :source} where source is
   :builtin, :user, or :project."
  [registry]
  (loader/discover-tools registry))

(defn reload-tools
  "Reload all tools from disk.

   Clears and reloads the entire registry.

   Returns:
   - {:loaded [tool-ids...] :failed [{:path :error}...]}"
  [registry]
  (loader/reload-all-tools registry))

;------------------------------------------------------------------------------ Layer 4
;; Agent and dashboard integration

(defn tools-for-context
  "Get tools available for a given capability context.

   Filters tools by:
   - Enabled status
   - Required capabilities match

   Arguments:
   - registry     - ToolRegistry instance
   - capabilities - Set of required capability keywords

   Returns sequence of tool configurations.

   Example:
     (tools-for-context registry #{:code/diagnostics :code/format})"
  [registry capabilities]
  (registry/tools-for-context registry capabilities))

(defn tools-with-status
  "Get all tools with their current runtime status.

   For LSP tools, includes server status.
   For function tools, always :available.

   Returns sequence of maps with :tool and :status keys.

   Example:
     (tools-with-status registry)
     ;; => [{:tool {...} :status :running}
     ;;     {:tool {...} :status :stopped}]"
  [registry]
  (registry/tools-with-status registry))

(defn registry-stats
  "Get statistics about the registry.

   Returns:
   - {:total count
      :by-type {:lsp n :function n ...}
      :enabled count
      :lsp-running count}"
  [registry]
  (registry/registry-stats registry))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a registry
  (def reg (create-registry {:auto-load? true}))

  ;; List all tools
  (list-tools reg)

  ;; Find LSP tools
  (find-tools reg {:type :lsp})

  ;; Find tools with specific capabilities
  (find-tools reg {:capabilities #{:code/diagnostics}})

  ;; Start Clojure LSP
  (start-lsp reg :lsp/clojure)

  ;; Check status
  (lsp-status reg :lsp/clojure)

  ;; Get stats
  (registry-stats reg)

  ;; Tools for agent context
  (tools-for-context reg #{:code/format})

  :leave-this-here)
