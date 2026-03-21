;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
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

(ns ai.miniforge.tool-registry.registry
  "In-memory tool registry implementation.

   Layer 0: Protocol definitions
   Layer 1: Registry record implementation
   Layer 2: Query and filter operations
   Layer 3: Status and statistics"
  (:require
   [ai.miniforge.tool-registry.schema :as schema]
   [ai.miniforge.logging.interface :as log]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol definitions

(defprotocol ToolRegistry
  "Protocol for tool registration and lookup."
  (register-tool [this tool] "Register a tool in the registry.")
  (unregister-tool [this tool-id] "Remove a tool from the registry.")
  (get-tool [this tool-id] "Retrieve a tool by ID.")
  (list-tools [this] "List all registered tools.")
  (find-tools [this query] "Find tools matching query.")
  (update-tool [this tool-id updates] "Update a tool's configuration.")
  (clear-tools [this] "Remove all tools from the registry."))

;------------------------------------------------------------------------------ Layer 1
;; Registry record implementation

(defrecord AtomToolRegistry [state]
  ToolRegistry

  (register-tool [_this tool]
    (let [{:keys [valid? errors]} (schema/validate-tool tool)]
      (when-not valid?
        (throw (ex-info "Invalid tool configuration"
                        {:tool-id (:tool/id tool)
                         :errors errors})))
      (when-not (schema/valid-tool-id? (:tool/id tool))
        (throw (ex-info "Tool ID must be a namespaced keyword"
                        {:tool-id (:tool/id tool)})))
      (let [normalized (schema/normalize-tool tool)
            tool-id (:tool/id normalized)
            logger (:logger @state)]
        (swap! (:tools @state) assoc tool-id normalized)
        (when logger
          (log/debug logger :system :tool-registry/registered
                     {:data {:tool-id tool-id}}))
        tool-id)))

  (unregister-tool [_this tool-id]
    (let [logger (:logger @state)]
      (swap! (:tools @state) dissoc tool-id)
      (when logger
        (log/debug logger :system :tool-registry/unregistered
                   {:data {:tool-id tool-id}}))
      tool-id))

  (get-tool [_this tool-id]
    (get @(:tools @state) tool-id))

  (list-tools [_this]
    (vals @(:tools @state)))

  (find-tools [this query]
    (let [tools (list-tools this)
          {:keys [type capabilities tags enabled text]} query]
      (->> tools
           (filter (fn [tool]
                     (and
                      ;; Type filter
                      (or (nil? type)
                          (= type (:tool/type tool)))
                      ;; Capabilities filter
                      (or (nil? capabilities)
                          (empty? capabilities)
                          (schema/has-all-capabilities? tool capabilities))
                      ;; Tags filter
                      (or (nil? tags)
                          (empty? tags)
                          (some (:tool/tags tool #{}) tags))
                      ;; Enabled filter
                      (or (nil? enabled)
                          (= enabled (schema/enabled? tool)))
                      ;; Text search
                      (or (nil? text)
                          (str/blank? text)
                          (let [q (str/lower-case text)]
                            (or (str/includes? (str/lower-case (or (:tool/name tool) "")) q)
                                (str/includes? (str/lower-case (or (:tool/description tool) "")) q)
                                (str/includes? (str/lower-case (str (:tool/id tool))) q)))))))
           (vec))))

  (update-tool [_this tool-id updates]
    (let [current (get @(:tools @state) tool-id)]
      (when-not current
        (throw (ex-info "Tool not found" {:tool-id tool-id})))
      (let [updated (merge current updates)
            {:keys [valid? errors]} (schema/validate-tool updated)]
        (when-not valid?
          (throw (ex-info "Invalid update" {:tool-id tool-id :errors errors})))
        (swap! (:tools @state) assoc tool-id updated)
        updated)))

  (clear-tools [_this]
    (reset! (:tools @state) {})))

(defn create-registry
  "Create a new tool registry.

   Options:
   - :auto-load?  - Load tools on creation (default: false)
   - :watch?      - Watch for file changes (default: false)
   - :logger      - Logger instance
   - :project-dir - Project directory for .miniforge/tools lookup

   Returns an AtomToolRegistry instance."
  ([] (create-registry {}))
  ([{:keys [auto-load? watch? logger project-dir]
     :or {auto-load? false watch? false}}]
   (let [tools-atom (atom {})
         state (atom {:tools tools-atom
                      :logger logger
                      :project-dir project-dir
                      :auto-load? auto-load?
                      :watch? watch?
                      ;; LSP server state
                      :lsp-servers (atom {})
                      ;; LSP manager state (populated by lsp/manager)
                      :lsp-manager nil})
         registry (->AtomToolRegistry state)]

     ;; Add self-referential functions for loader and LSP manager
     (swap! state assoc
            :register-fn (fn [tool] (register-tool registry tool))
            :clear-fn (fn [] (clear-tools registry))
            :get-tool-fn (fn [tool-id] (get-tool registry tool-id)))

     ;; Auto-load if requested (requires loader - deferred)
     ;; Watching is handled by watcher component (Phase 5)

     registry)))

;------------------------------------------------------------------------------ Layer 2
;; Query and filter operations

(defn tools-for-context
  "Get tools available for a given capability context.

   Arguments:
   - registry     - ToolRegistry instance
   - capabilities - Set of required capability keywords

   Returns sequence of enabled tools with matching capabilities."
  [registry capabilities]
  (find-tools registry {:capabilities capabilities
                        :enabled true}))

(defn lsp-tools
  "Get all LSP tools from the registry."
  [registry]
  (find-tools registry {:type :lsp}))

(defn enabled-tools
  "Get all enabled tools from the registry."
  [registry]
  (find-tools registry {:enabled true}))

;------------------------------------------------------------------------------ Layer 3
;; Status and statistics

(defn tools-with-status
  "Get all tools with their current runtime status.

   For LSP tools, checks server status.
   For function tools, returns :available.

   Returns sequence of {:tool ToolConfig :status keyword}."
  [registry]
  (let [lsp-manager (:lsp-manager @(:state registry))
        get-lsp-status (if lsp-manager
                         (fn [tool-id]
                           (try
                             ;; Will be implemented by lsp/manager
                             (if-let [status-fn (:status-fn lsp-manager)]
                               (status-fn tool-id)
                               :unknown)
                             (catch Exception _ :unknown)))
                         (constantly :unknown))]
    (for [tool (list-tools registry)]
      {:tool tool
       :status (case (:tool/type tool)
                 :lsp (get-lsp-status (:tool/id tool))
                 :function :available
                 :mcp :not-implemented
                 :external :not-implemented
                 :unknown)})))

(defn registry-stats
  "Get statistics about the registry.

   Returns:
   - {:total count
      :by-type {:lsp n :function n ...}
      :enabled count
      :lsp-running count}"
  [registry]
  (let [tools (list-tools registry)
        statuses (tools-with-status registry)
        lsp-running (count (filter #(= :running (:status %)) statuses))]
    {:total (count tools)
     :by-type (frequencies (map :tool/type tools))
     :enabled (count (filter schema/enabled? tools))
     :lsp-running lsp-running}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a registry
  (def reg (create-registry {:logger nil}))

  ;; Register a tool
  (register-tool reg
                 {:tool/id :lsp/clojure
                  :tool/type :lsp
                  :tool/name "Clojure LSP"
                  :tool/config {:lsp/command ["clojure-lsp"]}})

  ;; List tools
  (list-tools reg)

  ;; Find LSP tools
  (find-tools reg {:type :lsp})

  ;; Get stats
  (registry-stats reg)

  :leave-this-here)
