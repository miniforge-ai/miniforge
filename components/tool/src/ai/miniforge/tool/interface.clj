(ns ai.miniforge.tool.interface
  "Public API for the tool component.
   Provides tool definition, registration, and execution."
  (:require
   [ai.miniforge.tool.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Re-export protocols for extension

(def Tool
  "Tool protocol for implementing custom tools."
  core/Tool)

(def ToolRegistry
  "ToolRegistry protocol for implementing custom registries."
  core/ToolRegistry)

;------------------------------------------------------------------------------ Layer 1
;; Tool creation

(defn create-tool
  "Create a function-based tool.

   Options:
   - :id          - Namespaced keyword identifying the tool (required)
   - :name        - Display name (defaults to id name)
   - :description - What the tool does
   - :parameters  - Map of param specs {:param {:type :string :required true}}
   - :handler     - (fn [params context] -> result)
   - :metadata    - Optional metadata map

   Example:
     (create-tool
       {:id :tools/greet
        :description \"Greet a user\"
        :parameters {:name {:type :string :required true}}
        :handler (fn [params _ctx] (str \"Hello, \" (:name params) \"!\"))})"
  [opts]
  (core/create-function-tool opts))

;; Registry creation and management

(defn create-registry
  "Create a new tool registry.

   Options:
   - :logger - Optional logger for registration events

   Example:
     (create-registry)
     (create-registry {:logger my-logger})"
  ([] (core/create-registry))
  ([opts] (core/create-registry opts)))

(defn register!
  "Register a tool in a registry. Returns the tool ID."
  [registry tool]
  (core/register-tool registry tool))

(defn unregister!
  "Remove a tool from a registry. Returns the tool ID."
  [registry tool-id]
  (core/unregister-tool registry tool-id))

(defn get-tool
  "Retrieve a tool by ID from a registry.
   Returns nil if not found."
  [registry tool-id]
  (core/get-tool registry tool-id))

(defn list-tools
  "List all registered tools in a registry."
  [registry]
  (core/list-tools registry))

(defn find-tools
  "Find tools matching a query string.
   Searches tool names and descriptions."
  [registry query]
  (core/find-tools registry query))

;------------------------------------------------------------------------------ Layer 2
;; Tool execution

(defn execute
  "Execute a tool directly.

   Arguments:
   - tool    - A tool instance
   - params  - Parameters map
   - context - Execution context

   Returns:
   - On success: {:success true :result ...}
   - On failure: {:success false :error {...}}"
  [tool params context]
  (core/execute tool params context))

(defn execute-by-id
  "Execute a tool from a registry by ID.

   Arguments:
   - registry - Tool registry
   - tool-id  - Tool identifier keyword
   - params   - Parameters map
   - context  - Execution context

   Returns:
   - On success: {:success true :result ...}
   - On failure: {:success false :error {...}}"
  [registry tool-id params context]
  (core/execute-tool registry tool-id params context))

;; Tool introspection

(defn tool-id
  "Get the ID of a tool."
  [tool]
  (core/tool-id tool))

(defn tool-info
  "Get information about a tool.
   Returns map with :id, :name, :description, :parameters, :metadata."
  [tool]
  (core/tool-info tool))

;; Response helpers

(defn success?
  "Check if an execution result was successful."
  [result]
  (:success result))

(defn get-result
  "Extract the result from a successful execution."
  [result]
  (:result result))

(defn get-error
  "Extract error details from a failed execution."
  [result]
  (:error result))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a simple tool
  (def greet-tool
    (create-tool
     {:id :tools/greet
      :description "Greet a user by name"
      :parameters {:name {:type :string :required true}}
      :handler (fn [params _ctx]
                 (str "Hello, " (:name params) "!"))}))

  ;; Get tool info
  (tool-info greet-tool)

  ;; Execute directly
  (execute greet-tool {:name "Alice"} {})
  ;; => {:success true, :result "Hello, Alice!"}

  ;; Create a registry
  (def registry (create-registry))

  ;; Register the tool
  (register! registry greet-tool)

  ;; Execute via registry
  (execute-by-id registry :tools/greet {:name "Bob"} {})
  ;; => {:success true, :result "Hello, Bob!"}

  ;; Find tools
  (find-tools registry "greet")

  ;; Check result
  (let [result (execute-by-id registry :tools/greet {:name "Carol"} {})]
    (when (success? result)
      (println "Got:" (get-result result))))

  :leave-this-here)
