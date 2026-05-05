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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.validator :as validator]
   [ai.miniforge.workflow.schemas :as schemas])
  (:import
   [java.util.jar JarFile]))

;;------------------------------------------------------------------------------ Layer 0
;; Registry state

(defonce registry
  ;; Atom containing the workflow registry.
  ;; Map of workflow-id -> workflow definition
  (atom {}))

;;------------------------------------------------------------------------------ Layer 1
;; Workflow discovery

(defn jar-entry-names
  "List entry names under dir-prefix inside a JAR file."
  [^java.net.URL jar-url dir-prefix]
  (let [jar-path (-> (.getPath jar-url)
                     (str/replace #"^file:" "")
                     (str/replace #"!.*$" ""))]
    (with-open [jf (JarFile. jar-path)]
      (->> (enumeration-seq (.entries jf))
           (map #(.getName %))
           (filter #(str/starts-with? % dir-prefix))
           (remove #(= % dir-prefix))
           vec))))

(defn list-resource-names
  "List resource names under a classpath directory.
   Works for both filesystem directories and JAR entries."
  [dir-name]
  (let [dir-urls (enumeration-seq (.getResources (clojure.lang.RT/baseLoader) dir-name))
        prefix (if (str/ends-with? dir-name "/") dir-name (str dir-name "/"))]
    (when (seq dir-urls)
      (->> dir-urls
           (mapcat (fn [dir-url]
                     (case (.getProtocol dir-url)
                       "file" (let [dir-file (io/file (.getPath dir-url))]
                                (if (.isDirectory dir-file)
                                  (->> (.listFiles dir-file)
                                       (filter #(.isFile ^java.io.File %))
                                       (map #(.getName ^java.io.File %)))
                                  []))
                       "jar"  (->> (jar-entry-names dir-url prefix)
                                   (map #(str/replace-first % prefix ""))
                                   (remove #(str/includes? % "/")))
                       [])))
           distinct
           vec))))

(defn load-workflow-from-resource
  "Load workflow definition from classpath resource.

   Arguments:
     resource-path - Path to workflow EDN file

   Returns:
   - workflow definition map on success
   - nil when no resource exists at `resource-path`
   - a `:fault` anomaly when the resource exists but failed to parse
     (carrying `:resource-path` and `:error`)

   This is the canonical, anomaly-returning entry point. The
   in-component caller `discover-workflows-from-resources` filters
   anomalies out of its sequence rather than letting one bad resource
   abort discovery."
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (try
      (edn/read-string (slurp resource))
      (catch Exception e
        (anomaly/anomaly :fault
                         (str "Failed to load workflow from " resource-path)
                         {:resource-path resource-path
                          :error (ex-message e)})))))

(defn discover-workflows-from-resources
  "Discover workflows from classpath resources.
   The active workflow families are determined by whichever components contribute
   `workflows/*.edn` files to the classpath.

   Filters out resources that failed to parse (anomaly results from
   `load-workflow-from-resource`) so a single corrupt EDN file does
   not mask all workflows.

   Returns: Sequence of workflow maps"
  []
  (->> (list-resource-names "workflows")
       (filter #(str/ends-with? % ".edn"))
       (sort)
       (map #(str "workflows/" %))
       (keep load-workflow-from-resource)
       (remove anomaly/anomaly?)))

;;------------------------------------------------------------------------------ Layer 2
;; Workflow characteristics

(defn- compute-characteristics
  "Compute the raw characteristics map from a workflow definition,
   without schema validation. Pure data shaping."
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
                     :else :complex)]
    {:id (:workflow/id workflow)
     :version (:workflow/version workflow)
     :name (:workflow/name workflow)
     :description (:workflow/description workflow)
     :phases phase-count
     :max-iterations max-iterations
     :task-types (or task-types [])
     :complexity complexity
     :has-review has-review?
     :has-testing has-testing?}))

(defn try-workflow-characteristics
  "Anomaly-returning variant of `workflow-characteristics`.

   Returns:
   - on success: characteristics map validated against
     `WorkflowCharacteristics`
   - on schema failure: an `:invalid-input` anomaly carrying
     `:characteristics` and `:errors` (humanized malli explanation)

   This is the canonical, anomaly-returning entry point. The boundary
   site `workflow-characteristics` inlines a `response/throw-anomaly!`
   when an anomaly is observed, preserving the legacy thrown-exception
   contract for external callers that depend on a plain map."
  [workflow]
  (let [characteristics (compute-characteristics workflow)]
    (if (schemas/valid-characteristics? characteristics)
      characteristics
      (anomaly/anomaly :invalid-input
                       "Invalid workflow characteristics"
                       {:characteristics characteristics
                        :errors (schemas/explain-characteristics characteristics)}))))

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
      :has-testing boolean}

   Throws via `response/throw-anomaly!` with category
   `:anomalies.workflow/invalid-config` when the computed
   characteristics fail schema validation. Use
   `try-workflow-characteristics` for an anomaly-returning equivalent."
  [workflow]
  (let [result (try-workflow-characteristics workflow)]
    (if (anomaly/anomaly? result)
      (response/throw-anomaly! :anomalies.workflow/invalid-config
                               (:anomaly/message result)
                               (:anomaly/data result))
      result)))

;;------------------------------------------------------------------------------ Layer 3
;; Registry operations

(defn- registration-errors
  [workflow]
  (:errors (validator/validate-workflow workflow)))

(defn validate-workflow-registration
  "Validate a workflow definition for registration.

   Returns:
   - the workflow itself when validation passes
   - an `:invalid-input` anomaly carrying `:workflow-id` and
     `:validation/errors` when the validator reports errors

   This is the canonical, anomaly-returning entry point. The boundary
   site `register-workflow!` inlines a `response/throw-anomaly!` when
   an anomaly is observed."
  [workflow]
  (let [workflow-id (:workflow/id workflow)
        errors (registration-errors workflow)]
    (if (seq errors)
      (anomaly/anomaly :invalid-input
                       (messages/t :status/invalid-workflow-registration
                                   {:workflow-id workflow-id})
                       {:workflow-id workflow-id
                        :validation/errors errors})
      workflow)))

(defn missing-workflow-id-anomaly
  "Return an `:invalid-input` anomaly describing a workflow missing
   `:workflow/id`, or nil when the id is present.

   This is the canonical, anomaly-returning entry point. The boundary
   site `register-workflow!` inlines a `response/throw-anomaly!` on
   anomaly."
  [workflow]
  (when-not (:workflow/id workflow)
    (anomaly/anomaly :invalid-input
                     (messages/t :status/missing-workflow-id)
                     {:workflow workflow})))

(defn register-workflow!
  "Register a workflow in the registry.

   Arguments:
     workflow - Workflow definition map

   Returns: The registered workflow.

   Throws via `response/throw-anomaly!` with category
   `:anomalies/incorrect` when `:workflow/id` is missing, or
   `:anomalies.workflow/invalid-config` when validator reports errors.

   For anomaly-returning equivalents that callers can branch on as
   data, use `missing-workflow-id-anomaly` and
   `validate-workflow-registration`."
  [workflow]
  (when-let [a (missing-workflow-id-anomaly workflow)]
    (response/throw-anomaly! :anomalies/incorrect
                             (:anomaly/message a)
                             (:anomaly/data a)))
  (let [validated (validate-workflow-registration workflow)]
    (when (anomaly/anomaly? validated)
      (response/throw-anomaly! :anomalies.workflow/invalid-config
                               (:anomaly/message validated)
                               (:anomaly/data validated)))
    (swap! registry assoc (:workflow/id workflow) workflow)
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
