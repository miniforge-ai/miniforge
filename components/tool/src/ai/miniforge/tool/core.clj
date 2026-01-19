(ns ai.miniforge.tool.core
  "Tool protocol and registry implementation.
   Layer 0: Pure functions for tool definitions and validation
   Layer 1: Registry management and tool execution"
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Tool schema and validation

(def tool-schema
  "Schema for tool definitions."
  {:tool/id          :keyword
   :tool/name        :string
   :tool/description :string
   :tool/parameters  :map       ; parameter spec
   :tool/handler     :fn        ; (fn [params context] -> result)
   :tool/metadata    :map})     ; optional metadata

(defn valid-tool-id?
  "Check if tool ID is valid (namespaced keyword)."
  [id]
  (and (keyword? id)
       (namespace id)))

(defn validate-params
  "Validate parameters against a parameter spec.
   Returns {:valid true} or {:valid false :errors [...]}."
  [param-spec params]
  (let [required (set (keys (filter (fn [[_k v]] (:required v)) param-spec)))
        provided (set (keys params))
        missing (set/difference required provided)]
    (if (empty? missing)
      {:valid true}
      {:valid false
       :errors (mapv #(str "Missing required parameter: " (name %)) missing)})))

(defn build-tool-definition
  "Build a tool definition map from parts."
  [{:keys [id name description parameters handler metadata]
    :or {parameters {} metadata {}}}]
  {:tool/id id
   :tool/name (or name (clojure.core/name id))
   :tool/description description
   :tool/parameters parameters
   :tool/handler handler
   :tool/metadata metadata})

(defn tool-summary
  "Create a brief summary of a tool for display."
  [tool]
  (str (:tool/name tool) " - " (:tool/description tool)))

;------------------------------------------------------------------------------ Layer 1
;; Tool protocol and registry

(defprotocol Tool
  "Protocol for executable tools."
  (tool-id [this] "Return the tool's identifier.")
  (tool-info [this] "Return tool metadata and description.")
  (execute [this params context] "Execute the tool with given params and context."))

(defprotocol ToolRegistry
  "Protocol for tool registration and lookup."
  (register-tool [this tool] "Register a tool in the registry.")
  (unregister-tool [this tool-id] "Remove a tool from the registry.")
  (get-tool [this tool-id] "Retrieve a tool by ID.")
  (list-tools [this] "List all registered tools.")
  (find-tools [this query] "Find tools matching query."))

(defrecord FunctionTool [id name description parameters handler metadata]
  Tool
  (tool-id [_this] id)
  (tool-info [_this]
    {:id id
     :name name
     :description description
     :parameters parameters
     :metadata metadata})
  (execute [_this params context]
    (let [validation (validate-params parameters params)]
      (if (:valid validation)
        (try
          {:success true
           :result (handler params context)}
          (catch Exception e
            {:success false
             :error {:type "execution_error"
                     :message (.getMessage e)}}))
        {:success false
         :error {:type "validation_error"
                 :errors (:errors validation)}}))))

(defrecord AtomRegistry [tools-atom logger]
  ToolRegistry
  (register-tool [_this tool]
    (let [id (tool-id tool)]
      (swap! tools-atom assoc id tool)
      (when logger
        (log/debug logger :system :tool/registered
                   {:data {:tool-id id}}))
      id))

  (unregister-tool [_this tool-id]
    (swap! tools-atom dissoc tool-id)
    (when logger
      (log/debug logger :system :tool/unregistered
                 {:data {:tool-id tool-id}}))
    tool-id)

  (get-tool [_this tool-id]
    (get @tools-atom tool-id))

  (list-tools [_this]
    (vals @tools-atom))

  (find-tools [_this query]
    (let [q (str/lower-case (or query ""))]
      (->> @tools-atom
           vals
           (filter (fn [tool]
                     (let [info (tool-info tool)]
                       (or (str/includes? (str/lower-case (or (:name info) "")) q)
                           (str/includes? (str/lower-case (or (:description info) "")) q)))))))))

(defn create-function-tool
  "Create a function-based tool.

   Options:
   - :id          - Namespaced keyword identifying the tool (required)
   - :name        - Display name
   - :description - What the tool does
   - :parameters  - Map of param specs {:param {:type :string :required true}}
   - :handler     - (fn [params context] -> result)
   - :metadata    - Optional metadata map"
  [{:keys [id name description parameters handler metadata]
    :or {parameters {} metadata {}}}]
  (when-not (valid-tool-id? id)
    (throw (ex-info "Tool ID must be a namespaced keyword"
                    {:id id})))
  (->FunctionTool id
                  (or name (clojure.core/name id))
                  description
                  parameters
                  handler
                  metadata))

(defn create-registry
  "Create a new tool registry.

   Options:
   - :logger - Optional logger for registration events"
  ([] (create-registry {}))
  ([{:keys [logger]}]
   (->AtomRegistry (atom {}) logger)))

(defn execute-tool
  "Execute a tool from a registry by ID.
   Returns execution result or error if tool not found."
  [registry tool-id params context]
  (if-let [tool (get-tool registry tool-id)]
    (execute tool params context)
    {:success false
     :error {:type "not_found"
             :message (str "Tool not found: " tool-id)}}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a simple tool
  (def echo-tool
    (create-function-tool
     {:id :tools/echo
      :name "Echo"
      :description "Returns the input unchanged"
      :parameters {:message {:type :string :required true}}
      :handler (fn [params _ctx] (:message params))}))

  ;; Create a registry and register the tool
  (def registry (create-registry))
  (register-tool registry echo-tool)

  ;; Execute the tool
  (execute-tool registry :tools/echo {:message "Hello!"} {})
  ;; => {:success true, :result "Hello!"}

  ;; List tools
  (map tool-info (list-tools registry))

  ;; Find tools
  (map tool-info (find-tools registry "echo"))

  ;; Validation failure
  (execute-tool registry :tools/echo {} {})
  ;; => {:success false, :error {:type "validation_error", ...}}

  :leave-this-here)
