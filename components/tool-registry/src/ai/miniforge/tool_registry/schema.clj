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

(ns ai.miniforge.tool-registry.schema
  "Malli schemas for tool configurations.

   Layer 0: Enums and base types
   Layer 1: Type-specific config schemas (LSP, MCP, etc.)
   Layer 2: Base tool schema
   Layer 3: Validation functions
   Layer 4: Result builders"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Enums and base types

(def tool-types
  "Supported tool types."
  #{:function :lsp :mcp :external})

(def ToolType
  "Schema for tool type enum."
  [:enum :function :lsp :mcp :external])

(def lsp-capabilities
  "LSP server capabilities."
  #{:diagnostics :format :hover :completion
    :definition :references :rename
    :code-action :document-symbol :workspace-symbol
    :semantic-tokens :inlay-hints})

(def LSPCapability
  "Schema for LSP capability enum."
  [:enum :diagnostics :format :hover :completion
   :definition :references :rename
   :code-action :document-symbol :workspace-symbol
   :semantic-tokens :inlay-hints])

(def tool-capabilities
  "High-level tool capabilities for agent matching."
  #{:code/diagnostics :code/format :code/hover
    :code/completion :code/navigation :code/refactor
    :code/symbols :code/semantics})

(def ToolCapability
  "Schema for tool capability enum."
  [:enum :code/diagnostics :code/format :code/hover
   :code/completion :code/navigation :code/refactor
   :code/symbols :code/semantics])

(def start-modes
  "LSP server start modes."
  #{:on-demand :eager})

(def StartMode
  "Schema for start mode enum."
  [:enum :on-demand :eager])

;------------------------------------------------------------------------------ Layer 1
;; Type-specific config schemas

(def LSPConfig
  "Schema for LSP-specific configuration.

   Example:
     {:lsp/command [\"clojure-lsp\"]
      :lsp/languages #{\"clojure\" \"clojurescript\"}
      :lsp/file-patterns [\"**/*.clj\" \"**/*.cljs\"]
      :lsp/capabilities #{:diagnostics :format :hover}
      :lsp/start-mode :on-demand
      :lsp/shutdown-after-ms 300000}"
  [:map
   [:lsp/command [:vector :string]]
   [:lsp/languages {:optional true} [:set :string]]
   [:lsp/file-patterns {:optional true} [:vector :string]]
   [:lsp/capabilities {:optional true} [:set LSPCapability]]
   [:lsp/start-mode {:optional true :default :on-demand} StartMode]
   [:lsp/shutdown-after-ms {:optional true :default 300000} :int]
   [:lsp/init-options {:optional true} :map]
   [:lsp/env {:optional true} [:map-of :string :string]]
   [:lsp/working-dir {:optional true} :string]])

(def FunctionConfig
  "Schema for function tool configuration.

   Example:
     {:function/handler-ns \"my.ns\"
      :function/handler-fn \"my-handler\"
      :function/parameters {:name {:type :string :required true}}}"
  [:map
   [:function/handler-ns {:optional true} :string]
   [:function/handler-fn {:optional true} :string]
   [:function/handler {:optional true} fn?]
   [:function/parameters {:optional true} :map]])

(def MCPConfig
  "Schema for MCP (Model Context Protocol) configuration.

   Example:
     {:mcp/command [\"npx\" \"@github/mcp-server\"]
      :mcp/transport :stdio}"
  [:map
   [:mcp/command [:vector :string]]
   [:mcp/transport {:optional true :default :stdio} [:enum :stdio :sse]]
   [:mcp/env {:optional true} [:map-of :string :string]]])

(def ExternalConfig
  "Schema for external process tool configuration.

   Example:
     {:external/command [\"my-tool\" \"--json\"]
      :external/timeout-ms 30000}"
  [:map
   [:external/command [:vector :string]]
   [:external/timeout-ms {:optional true :default 30000} :int]
   [:external/env {:optional true} [:map-of :string :string]]
   [:external/working-dir {:optional true} :string]])

;------------------------------------------------------------------------------ Layer 2
;; Base tool schema

(def ToolConfig
  "Schema for tool configuration.

   Example:
     {:tool/id :lsp/clojure
      :tool/type :lsp
      :tool/name \"Clojure LSP\"
      :tool/description \"Language server for Clojure\"
      :tool/version \"1.0.0\"
      :tool/config {:lsp/command [\"clojure-lsp\"] ...}
      :tool/capabilities #{:code/diagnostics :code/format}
      :tool/requires #{:subprocess/spawn}
      :tool/enabled true
      :tool/tags #{:language-server :clojure}}"
  [:map
   [:tool/id :keyword]
   [:tool/type ToolType]
   [:tool/name :string]
   [:tool/description {:optional true} [:maybe :string]]
   [:tool/version {:optional true :default "1.0.0"} [:maybe :string]]
   [:tool/config {:optional true} [:maybe :map]]
   [:tool/capabilities {:optional true} [:maybe [:set ToolCapability]]]
   [:tool/requires {:optional true} [:maybe [:set :keyword]]]
   [:tool/enabled {:optional true :default true} [:maybe :boolean]]
   [:tool/tags {:optional true} [:maybe [:set :keyword]]]
   [:tool/metadata {:optional true} [:maybe :map]]])

(def ToolConfigWithType
  "Schema that validates tool/config matches tool/type."
  [:and
   ToolConfig
   [:fn {:error/message "tool/config must match tool/type"}
    (fn [{:tool/keys [type config]}]
      (case type
        :lsp (or (nil? config) (m/validate LSPConfig config))
        :function (or (nil? config) (m/validate FunctionConfig config))
        :mcp (or (nil? config) (m/validate MCPConfig config))
        :external (or (nil? config) (m/validate ExternalConfig config))
        true))]])

;------------------------------------------------------------------------------ Layer 3
;; Validation functions

(defn validate-tool
  "Validate a tool configuration.

   Returns {:valid? true} or {:valid? false :errors [...]}."
  [tool]
  (if (m/validate ToolConfigWithType tool)
    {:valid? true}
    {:valid? false
     :errors (me/humanize (m/explain ToolConfigWithType tool))}))

(defn validate-lsp-config
  "Validate LSP-specific configuration.

   Returns {:valid? true} or {:valid? false :errors [...]}."
  [config]
  (if (m/validate LSPConfig config)
    {:valid? true}
    {:valid? false
     :errors (me/humanize (m/explain LSPConfig config))}))

(defn valid-tool?
  "Check if a tool configuration is valid."
  [tool]
  (:valid? (validate-tool tool)))

(defn valid-tool-id?
  "Check if a tool ID is valid (namespaced keyword)."
  [id]
  (and (keyword? id)
       (namespace id)))

(defn lsp-tool?
  "Check if a tool is an LSP tool."
  [tool]
  (= :lsp (:tool/type tool)))

(defn function-tool?
  "Check if a tool is a function tool."
  [tool]
  (= :function (:tool/type tool)))

(defn enabled?
  "Check if a tool is enabled."
  [tool]
  (get tool :tool/enabled true))

(defn has-capability?
  "Check if a tool has a specific capability."
  [tool capability]
  (contains? (or (:tool/capabilities tool) #{}) capability))

(defn has-all-capabilities?
  "Check if a tool has all specified capabilities."
  [tool capabilities]
  (every? #(has-capability? tool %) capabilities))

;------------------------------------------------------------------------------ Layer 4
;; Result builders and normalization

(defn success
  "Build a success result with a keyed value."
  [key value & {:as extras}]
  (merge {:success? true key value} extras))

(defn failure
  "Build a failure result with a keyed nil."
  [key error]
  {:success? false key nil :error error})

(defn failure-with-errors
  "Build a failure result with multiple errors."
  [key errors]
  {:success? false key nil :errors errors})

;; Simple result builders (no keyed value)

(defn ok
  "Build a simple success result.

   Examples:
     (ok)                        ;; => {:success? true}
     (ok :client lsp-client)     ;; => {:success? true :client lsp-client}
     (ok :a 1 :b 2)              ;; => {:success? true :a 1 :b 2}"
  [& {:as kvs}]
  (merge {:success? true} kvs))

(defn err
  "Build a simple error result.

   Examples:
     (err \"Something failed\")
     (err \"Tool not found: \" tool-id)
     (err :code -1 \"Parse error\")"
  ([message]
   {:success? false :error message})
  ([message-or-key & args]
   (if (keyword? message-or-key)
     ;; (err :code -1 "message")
     (let [[code message] args]
       {:success? false :error message :code code})
     ;; (err "Tool not found: " tool-id)
     {:success? false :error (apply str message-or-key args)})))

(defn normalize-tool
  "Normalize a tool configuration, ensuring defaults.

   Applies:
   - Default :tool/enabled true
   - Default :tool/version \"1.0.0\"
   - Ensures :tool/capabilities is a set
   - Ensures :tool/tags is a set
   - Ensures :tool/requires is a set"
  [tool]
  (-> tool
      (update :tool/enabled #(if (nil? %) true %))
      (update :tool/version #(or % "1.0.0"))
      (update :tool/capabilities #(when % (if (set? %) % (set %))))
      (update :tool/tags #(when % (if (set? %) % (set %))))
      (update :tool/requires #(when % (if (set? %) % (set %))))))

(defn tool-summary
  "Create a brief summary of a tool for display."
  [{:tool/keys [id name type]}]
  (str (or name (clojure.core/name id)) " (" (clojure.core/name type) ")"))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate a tool config
  (validate-tool
   {:tool/id :lsp/clojure
    :tool/type :lsp
    :tool/name "Clojure LSP"
    :tool/config {:lsp/command ["clojure-lsp"]}})

  ;; Invalid tool - missing required fields
  (validate-tool {:tool/id :test})

  ;; Check tool capabilities
  (has-capability?
   {:tool/id :lsp/clojure
    :tool/capabilities #{:code/diagnostics :code/format}}
   :code/format)

  ;; Normalize a tool
  (normalize-tool
   {:tool/id :lsp/test
    :tool/type :lsp
    :tool/name "Test"
    :tool/capabilities [:code/format]}) ; vector -> set

  :leave-this-here)
