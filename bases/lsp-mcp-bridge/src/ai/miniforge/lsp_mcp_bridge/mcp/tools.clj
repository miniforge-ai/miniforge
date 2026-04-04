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

(ns ai.miniforge.lsp-mcp-bridge.mcp.tools
  "MCP tool definitions and handlers.

   Each tool maps an MCP tool invocation to an LSP operation.

   Layer 0: Tool definitions (schemas)
   Layer 1: Tool handlers
   Layer 2: Tool dispatch"
  (:require
   [ai.miniforge.lsp-mcp-bridge.lsp.manager :as manager]
   [ai.miniforge.lsp-mcp-bridge.lsp.client :as client]
   [ai.miniforge.lsp-mcp-bridge.mcp.protocol :as mcp-proto]
   [ai.miniforge.response.interface :as response]
   [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0
;; Tool definitions

(def tool-definitions
  "MCP tool definitions with JSON Schema for inputSchema."
  [{:name "lsp_hover"
    :description "Get type information, documentation, and details about the symbol at a given position in a file."
    :inputSchema
    {:type "object"
     :properties
     {:file_path {:type "string" :description "Absolute path to the file"}
      :line {:type "integer" :description "0-based line number"}
      :character {:type "integer" :description "0-based character offset"}}
     :required ["file_path" "line" "character"]}}

   {:name "lsp_definition"
    :description "Find the definition location of the symbol at a given position. Returns the file path and position of the definition."
    :inputSchema
    {:type "object"
     :properties
     {:file_path {:type "string" :description "Absolute path to the file"}
      :line {:type "integer" :description "0-based line number"}
      :character {:type "integer" :description "0-based character offset"}}
     :required ["file_path" "line" "character"]}}

   {:name "lsp_references"
    :description "Find all references (usages) of the symbol at a given position across the project."
    :inputSchema
    {:type "object"
     :properties
     {:file_path {:type "string" :description "Absolute path to the file"}
      :line {:type "integer" :description "0-based line number"}
      :character {:type "integer" :description "0-based character offset"}
      :include_declaration {:type "boolean" :description "Include the declaration itself (default true)"}}
     :required ["file_path" "line" "character"]}}

   {:name "lsp_rename"
    :description "Rename a symbol at a given position. Returns the workspace edit (set of file changes) needed to rename the symbol everywhere."
    :inputSchema
    {:type "object"
     :properties
     {:file_path {:type "string" :description "Absolute path to the file"}
      :line {:type "integer" :description "0-based line number"}
      :character {:type "integer" :description "0-based character offset"}
      :new_name {:type "string" :description "The new name for the symbol"}}
     :required ["file_path" "line" "character" "new_name"]}}

   {:name "lsp_format"
    :description "Format a file using the language server's formatter. Returns the text edits to apply."
    :inputSchema
    {:type "object"
     :properties
     {:file_path {:type "string" :description "Absolute path to the file"}
      :tab_size {:type "integer" :description "Tab size (default 2)"}
      :insert_spaces {:type "boolean" :description "Use spaces instead of tabs (default true)"}}
     :required ["file_path"]}}

   {:name "lsp_document_symbols"
    :description "Get all symbols (functions, classes, variables, etc.) defined in a file. Useful for understanding file structure."
    :inputSchema
    {:type "object"
     :properties
     {:file_path {:type "string" :description "Absolute path to the file"}}
     :required ["file_path"]}}

   {:name "lsp_code_actions"
    :description "Get available code actions (quick fixes, refactorings) for a given range in a file."
    :inputSchema
    {:type "object"
     :properties
     {:file_path {:type "string" :description "Absolute path to the file"}
      :start_line {:type "integer" :description "0-based start line"}
      :start_character {:type "integer" :description "0-based start character"}
      :end_line {:type "integer" :description "0-based end line"}
      :end_character {:type "integer" :description "0-based end character"}}
     :required ["file_path" "start_line" "start_character" "end_line" "end_character"]}}

   {:name "lsp_completion"
    :description "Get code completion suggestions at a given position in a file."
    :inputSchema
    {:type "object"
     :properties
     {:file_path {:type "string" :description "Absolute path to the file"}
      :line {:type "integer" :description "0-based line number"}
      :character {:type "integer" :description "0-based character offset"}}
     :required ["file_path" "line" "character"]}}

   {:name "lsp_diagnostics"
    :description "Get diagnostics (errors, warnings, hints) for a file from the language server."
    :inputSchema
    {:type "object"
     :properties
     {:file_path {:type "string" :description "Absolute path to the file"}}
     :required ["file_path"]}}

   {:name "lsp_servers"
    :description "List all available LSP servers, their supported languages, and current status (running/stopped)."
    :inputSchema
    {:type "object"
     :properties {}
     :required []}}])

(def diagnostics-wait-ms
  "Time to wait for diagnostics to arrive via notification."
  2000)

;------------------------------------------------------------------------------ Layer 1
;; Tool handlers

(defn format-result
  "Format an LSP result as MCP response. Checks for anomaly maps."
  [result]
  (if (response/anomaly-map? result)
    (mcp-proto/tool-call-error (:anomaly/message result))
    (mcp-proto/tool-call-result
     (json/generate-string (:result result) {:pretty true}))))

(defn ensure-and-call
  "Resolve LSP client for file, ensure document is open, and call the LSP method.

   Arguments:
   - mgr       - Manager state
   - file-path - Absolute file path
   - lsp-fn    - Function (fn [client uri] ...) that calls the LSP method"
  [mgr file-path lsp-fn]
  (let [resolve-result (manager/resolve-client-for-file mgr file-path)]
    (if (response/anomaly-map? resolve-result)
      (mcp-proto/tool-call-error (:anomaly/message resolve-result))
      (let [{:keys [client tool-id]} resolve-result
            uri (str "file://" file-path)]
        (manager/ensure-document-open mgr tool-id client file-path)
        (format-result (lsp-fn client uri))))))

(defmulti handle-tool
  "Handle an MCP tool call. Dispatches on tool name."
  (fn [tool-name _args _manager] tool-name))

(defmethod handle-tool "lsp_hover"
  [_ args mgr]
  (ensure-and-call mgr (:file_path args)
    (fn [c uri] (client/hover c uri (:line args) (:character args)))))

(defmethod handle-tool "lsp_definition"
  [_ args mgr]
  (ensure-and-call mgr (:file_path args)
    (fn [c uri] (client/definition c uri (:line args) (:character args)))))

(defmethod handle-tool "lsp_references"
  [_ args mgr]
  (ensure-and-call mgr (:file_path args)
    (fn [c uri]
      (client/references c uri (:line args) (:character args)
                         (get args :include_declaration true)))))

(defmethod handle-tool "lsp_rename"
  [_ args mgr]
  (ensure-and-call mgr (:file_path args)
    (fn [c uri]
      (client/rename-symbol c uri (:line args) (:character args) (:new_name args)))))

(defmethod handle-tool "lsp_format"
  [_ args mgr]
  (ensure-and-call mgr (:file_path args)
    (fn [c uri]
      (client/format-document c uri {:tabSize (get args :tab_size 2)
                                     :insertSpaces (get args :insert_spaces true)}))))

(defmethod handle-tool "lsp_document_symbols"
  [_ args mgr]
  (ensure-and-call mgr (:file_path args)
    (fn [c uri] (client/document-symbols c uri))))

(defmethod handle-tool "lsp_code_actions"
  [_ args mgr]
  (ensure-and-call mgr (:file_path args)
    (fn [c uri]
      (client/code-actions c uri
                           {:start {:line (:start_line args) :character (:start_character args)}
                            :end {:line (:end_line args) :character (:end_character args)}}
                           []))))

(defmethod handle-tool "lsp_completion"
  [_ args mgr]
  (ensure-and-call mgr (:file_path args)
    (fn [c uri] (client/completion c uri (:line args) (:character args)))))

(defmethod handle-tool "lsp_diagnostics"
  [_ args mgr]
  (ensure-and-call mgr (:file_path args)
    (fn [c uri]
      (Thread/sleep diagnostics-wait-ms)
      {:result (client/get-diagnostics c uri)})))

(defmethod handle-tool "lsp_servers"
  [_ _args mgr]
  (let [running (manager/list-servers mgr)
        available (manager/available-tools mgr)
        result {:available (mapv (fn [t]
                                   {:tool-id (name (:tool/id t))
                                    :name (:tool/name t)
                                    :languages (vec (get-in t [:tool/config :lsp/languages]))
                                    :running? (some #(= (:tool-id %) (:tool/id t)) running)})
                                 available)
                :running (mapv (fn [s]
                                 {:tool-id (name (:tool-id s))
                                  :alive? (:alive? s)
                                  :uptime-ms (:uptime-ms s)
                                  :open-docs (:open-docs s)})
                               running)}]
    (mcp-proto/tool-call-result
     (json/generate-string result {:pretty true}))))

(defmethod handle-tool :default
  [tool-name _ _]
  (mcp-proto/tool-call-error (str "Unknown tool: " tool-name)))

;------------------------------------------------------------------------------ Layer 2
;; Tool dispatch

(defn call-tool
  "Dispatch an MCP tools/call request.

   Arguments:
   - params  - MCP tools/call params map with :name and :arguments
   - manager - LSP manager state

   Returns MCP tool result map."
  [params manager]
  (let [tool-name (:name params)
        args (or (:arguments params) {})]
    (try
      (handle-tool tool-name args manager)
      (catch Exception e
        (mcp-proto/tool-call-error
         (str "Tool error: " (.getMessage e)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (count tool-definitions) ;; => 10
  (map :name tool-definitions)

  :leave-this-here)
