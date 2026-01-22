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

(ns ai.miniforge.workflow.loader
  "Workflow configuration loading and caching.
   Loads workflows from resources or heuristic store."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [ai.miniforge.workflow.validator :as validator]
   [ai.miniforge.heuristic.interface :as heuristic]))

;------------------------------------------------------------------------------ Layer 0
;; Cache

(def ^:private workflow-cache
  "In-memory cache of loaded workflows.
   Map of [workflow-id version] -> workflow-config"
  (atom {}))

(defn clear-cache!
  "Clear the workflow cache. Useful for testing."
  []
  (reset! workflow-cache {}))

;------------------------------------------------------------------------------ Layer 1
;; Resource loading

(defn load-from-resource
  "Load workflow config from classpath resources.
   Looks for workflows/<workflow-id>.edn

   Arguments:
   - workflow-id: Workflow identifier (keyword)
   - version: Version string (currently ignored, uses latest from resource)

   Returns workflow config map or nil if not found."
  [workflow-id _version]
  (let [resource-path (str "workflows/" (name workflow-id) ".edn")]
    (when-let [resource (io/resource resource-path)]
      (try
        (with-open [rdr (io/reader resource)]
          (edn/read (java.io.PushbackReader. rdr)))
        (catch Exception e
          (throw (ex-info "Failed to load workflow from resource"
                          {:workflow-id workflow-id
                           :resource-path resource-path
                           :error (.getMessage e)}
                          e)))))))

;------------------------------------------------------------------------------ Layer 2
;; Heuristic store loading

(defn load-from-store
  "Load workflow config from heuristic store.

   Arguments:
   - workflow-id: Workflow identifier (keyword)
   - version: Version string or :latest
   - opts: Options map with optional :store

   Returns workflow config map or nil if not found."
  [workflow-id version opts]
  (try
    (let [heuristic-type (keyword "workflow" (name workflow-id))
          workflow-data (heuristic/get-heuristic heuristic-type version opts)]
      (:data workflow-data))
    (catch Exception _e
      ;; Heuristic not found or store error - return nil
      nil)))

;------------------------------------------------------------------------------ Layer 3
;; Combined loading with caching

(defn load-workflow
  "Load workflow config with caching.
   Tries resource first, then heuristic store.

   Arguments:
   - workflow-id: Workflow identifier (keyword)
   - version: Version string or :latest
   - opts: Options map
     - :store - Optional heuristic store
     - :skip-cache? - Skip cache lookup and refresh
     - :skip-validation? - Skip validation

   Returns:
   {:workflow workflow-config
    :source :resource | :store | :cache
    :validation validation-result}

   Throws ex-info if workflow not found or validation fails."
  [workflow-id version opts]
  (let [cache-key [workflow-id version]
        skip-cache? (:skip-cache? opts false)
        skip-validation? (:skip-validation? opts false)]

    ;; Check cache first (unless skip-cache?)
    (if-let [cached-workflow (and (not skip-cache?)
                                  (get @workflow-cache cache-key))]
      {:workflow cached-workflow
       :source :cache
       :validation {:valid? true :errors []}}

      ;; Try loading from resource or store
      (if-let [workflow (or (load-from-resource workflow-id version)
                            (load-from-store workflow-id version opts))]
        (let [;; Validate workflow
              validation (if skip-validation?
                           {:valid? true :errors []}
                           (validator/validate-workflow workflow))]

          ;; Check validation result
          (when-not (:valid? validation)
            (throw (ex-info "Workflow validation failed"
                            {:workflow-id workflow-id
                             :version version
                             :errors (:errors validation)})))

          ;; Cache and return
          (swap! workflow-cache assoc cache-key workflow)
          {:workflow workflow
           :source (if (load-from-resource workflow-id version) :resource :store)
           :validation validation})

        ;; Not found
        (throw (ex-info "Workflow not found"
                        {:workflow-id workflow-id
                         :version version}))))))

;------------------------------------------------------------------------------ Layer 4
;; Workflow discovery

(defn list-available-workflows
  "List available workflows from resources.

   Returns vector of workflow metadata maps:
   [{:workflow/id keyword
     :workflow/version string
     :workflow/type keyword
     :workflow/description string}]"
  []
  (let [workflows-dir (io/resource "workflows")]
    (if workflows-dir
      (let [workflows-path (.getPath workflows-dir)
            workflow-files (file-seq (io/file workflows-path))]
        (->> workflow-files
             (filter #(.isFile %))
             (filter #(.endsWith (.getName %) ".edn"))
             (map (fn [file]
                    (try
                      (with-open [rdr (io/reader file)]
                        (let [config (edn/read (java.io.PushbackReader. rdr))]
                          (select-keys config [:workflow/id
                                               :workflow/version
                                               :workflow/type
                                               :workflow/description
                                               :workflow/metadata])))
                      (catch Exception _e
                        nil))))
             (filter some?)
             vec))
      [])))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load workflow from resource
  (def workflow-result
    (load-workflow :canonical-sdlc-v1 "1.0.0" {}))

  (:source workflow-result)
  (:workflow workflow-result)
  (:validation workflow-result)

  ;; List available workflows
  (list-available-workflows)

  ;; Clear cache
  (clear-cache!)

  ;; Test cache behavior
  (def result1 (load-workflow :canonical-sdlc-v1 "1.0.0" {}))
  (def result2 (load-workflow :canonical-sdlc-v1 "1.0.0" {}))
  (= :resource (:source result1))
  (= :cache (:source result2))

  ;; Skip cache
  (def result3 (load-workflow :canonical-sdlc-v1 "1.0.0" {:skip-cache? true}))
  (= :resource (:source result3))

  :end)
