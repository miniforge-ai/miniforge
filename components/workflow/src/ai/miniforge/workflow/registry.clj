(ns ai.miniforge.workflow.registry
  "Centralized workflow registry for dynamic workflow discovery and management.

   Provides a single source of truth for available workflows, enabling:
   - Dynamic workflow discovery from resources
   - Workflow metadata extraction
   - Extensibility for plugin workflows
   - Workflow characteristics for selection

   Workflow characteristics are validated against malli schemas in workflow.schemas."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.workflow.schemas :as schemas]))

;;------------------------------------------------------------------------------ Layer 0
;; Registry state

(defonce ^:private registry
  ;; Atom containing the workflow registry.
  ;; Map of workflow-id -> workflow definition
  (atom {}))

;;------------------------------------------------------------------------------ Layer 1
;; Workflow discovery

(defn- load-workflow-from-resource
  "Load workflow definition from classpath resource.

   Arguments:
     resource-path - Path to workflow EDN file

   Returns: Workflow map or nil if not found"
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (try
      (edn/read-string (slurp resource))
      (catch Exception e
        (throw (ex-info (str "Failed to load workflow from " resource-path)
                        {:resource-path resource-path
                         :error (ex-message e)} e))))))

(defn- load-workflow-registry-config
  "Load workflow registry configuration from resources.

   Returns: Vector of workflow names or default list"
  []
  (if-let [resource (io/resource "config/workflow-registry.edn")]
    (try
      (:workflows (edn/read-string (slurp resource)))
      (catch Exception _e
        ;; Fallback to hardcoded list if config loading fails
        ["simple-test-v1.0.0"
         "minimal-test-v1.0.0"
         "quick-fix-v2.0.0"
         "lean-sdlc-v1.0.0"
         "standard-sdlc-v2.0.0"
         "canonical-sdlc-v1.0.0"]))
    ;; Fallback if resource not found
    ["simple-test-v1.0.0"
     "minimal-test-v1.0.0"
     "quick-fix-v2.0.0"
     "lean-sdlc-v1.0.0"
     "standard-sdlc-v2.0.0"
     "canonical-sdlc-v1.0.0"]))

(defn- discover-workflows-from-resources
  "Discover workflows from classpath resources.
   Workflow names are loaded from resources/config/workflow-registry.edn

   Returns: Sequence of workflow maps"
  []
  (let [workflow-names (load-workflow-registry-config)]
    (->> workflow-names
         (map #(str "workflows/" % ".edn"))
         (keep load-workflow-from-resource))))

;;------------------------------------------------------------------------------ Layer 2
;; Workflow characteristics

(defn workflow-characteristics
  "Extract characteristics from workflow for selection.

   Arguments:
     workflow - Workflow definition map

   Returns: Map with characteristics for selection (validated against WorkflowCharacteristics schema)
     {:id keyword
      :version string
      :name string
      :description string
      :phases int
      :max-iterations int
      :task-types [keywords]
      :complexity :simple | :medium | :complex
      :has-review boolean
      :has-testing boolean}"
  [workflow]
  (let [phases (:workflow/phases workflow)
        phase-count (count phases)
        max-iterations (get-in workflow [:workflow/config :max-total-iterations] 20)
        task-types (:workflow/task-types workflow)
        has-review? (some #(str/includes? (str (:phase/id %)) "review") phases)
        has-testing? (some #(str/includes? (str (:phase/id %)) "test") phases)
        complexity (cond
                     (< phase-count 4) :simple
                     (< phase-count 8) :medium
                     :else :complex)
        characteristics {:id (:workflow/id workflow)
                         :version (:workflow/version workflow)
                         :name (:workflow/name workflow)
                         :description (:workflow/description workflow)
                         :phases phase-count
                         :max-iterations max-iterations
                         :task-types (or task-types [])
                         :complexity complexity
                         :has-review has-review?
                         :has-testing has-testing?}]
    ;; Validate against schema
    (when-not (schemas/valid-characteristics? characteristics)
      (throw (ex-info "Invalid workflow characteristics"
                      {:characteristics characteristics
                       :errors (schemas/explain-characteristics characteristics)})))
    characteristics))

;;------------------------------------------------------------------------------ Layer 3
;; Registry operations

(defn register-workflow!
  "Register a workflow in the registry.

   Arguments:
     workflow - Workflow definition map

   Returns: The registered workflow"
  [workflow]
  (let [id (:workflow/id workflow)]
    (when-not id
      (throw (ex-info "Workflow must have :workflow/id" {:workflow workflow})))
    (swap! registry assoc id workflow)
    workflow))

(defn get-workflow
  "Get a workflow by ID from the registry.

   Arguments:
     workflow-id - Keyword workflow ID

   Returns: Workflow definition map or nil"
  [workflow-id]
  (get @registry workflow-id))

(defn list-workflows
  "List all registered workflows.

   Returns: Sequence of workflow definition maps"
  []
  (vals @registry))

(defn list-workflow-ids
  "List all registered workflow IDs.

   Returns: Sequence of workflow ID keywords"
  []
  (keys @registry))

(defn workflow-exists?
  "Check if a workflow is registered.

   Arguments:
     workflow-id - Keyword workflow ID

   Returns: Boolean"
  [workflow-id]
  (contains? @registry workflow-id))

(defn unregister-workflow!
  "Remove a workflow from the registry.

   Arguments:
     workflow-id - Keyword workflow ID

   Returns: The unregistered workflow or nil"
  [workflow-id]
  (let [workflow (get-workflow workflow-id)]
    (swap! registry dissoc workflow-id)
    workflow))

(defn clear-registry!
  "Clear all workflows from the registry.

   Returns: nil"
  []
  (reset! registry {})
  nil)

;;------------------------------------------------------------------------------ Layer 4
;; Initialization

(defn initialize-registry!
  "Initialize the registry with workflows from resources.

   Returns: Number of workflows loaded"
  []
  (let [workflows (discover-workflows-from-resources)]
    (doseq [workflow workflows]
      (register-workflow! workflow))
    (count workflows)))

(defn ensure-initialized!
  "Ensure the registry is initialized (idempotent).

   Returns: Number of workflows in registry"
  []
  (when (empty? @registry)
    (initialize-registry!))
  (count @registry))

;;------------------------------------------------------------------------------ Layer 5
;; Query functions

(defn get-workflows-by-task-type
  "Get workflows suitable for a task type.

   Arguments:
     task-type - Keyword task type (e.g., :feature, :bugfix, :refactor)

   Returns: Sequence of workflow definition maps"
  [task-type]
  (ensure-initialized!)
  (filter #(some #{task-type} (:workflow/task-types %)) (list-workflows)))

(defn get-workflows-by-complexity
  "Get workflows by complexity level.

   Arguments:
     complexity - Keyword :simple, :medium, or :complex

   Returns: Sequence of workflow definition maps"
  [complexity]
  (ensure-initialized!)
  (filter #(= complexity (:complexity (workflow-characteristics %))) (list-workflows)))

(defn get-simplest-workflow
  "Get the simplest workflow (fewest phases).

   Returns: Workflow definition map"
  []
  (ensure-initialized!)
  (first (sort-by #(count (:workflow/phases %)) (list-workflows))))

(defn get-most-comprehensive-workflow
  "Get the most comprehensive workflow (most phases).

   Returns: Workflow definition map"
  []
  (ensure-initialized!)
  (first (sort-by #(- (count (:workflow/phases %))) (list-workflows))))
