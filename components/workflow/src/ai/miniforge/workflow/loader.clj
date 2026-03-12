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

(ns ai.miniforge.workflow.loader
  "Workflow configuration loading and caching.
   Loads workflows from resources or heuristic store."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [ai.miniforge.workflow.validator :as validator]
   [ai.miniforge.heuristic.interface :as heuristic])
  (:import
   [java.util.jar JarFile]))

;------------------------------------------------------------------------------ Layer 0
;; Cache

(def workflow-cache
  "In-memory cache of loaded workflows.
   Map of [workflow-id version] -> workflow-config"
  (atom {}))

(defn clear-cache!
  "Clear the workflow cache. Useful for testing."
  []
  (reset! workflow-cache {}))

;------------------------------------------------------------------------------ Layer 1
;; Resource loading

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

(defn latest?
  "True when version represents 'latest' (keyword or string)."
  [version]
  (or (= version :latest) (= version "latest")))

(defn find-latest-versioned-resource
  "Scan classpath for the highest versioned workflow file matching workflow-id.
   Looks for workflows/<workflow-id>-v*.edn files and returns the path with
   the highest version number. Works inside uberjars."
  [workflow-id]
  (let [prefix (str (name workflow-id) "-v")
        candidates (->> (list-resource-names "workflows")
                        (filter #(str/ends-with? % ".edn"))
                        (filter #(str/starts-with? % prefix))
                        sort
                        reverse)]
    (when-let [filename (first candidates)]
      (str "workflows/" filename))))

(defn load-from-resource
  "Load workflow config from classpath resources.
   Looks for workflows/<workflow-id>-v<version>.edn first,
   then falls back to workflows/<workflow-id>.edn,
   then scans for highest versioned file when version is 'latest'.

   Arguments:
   - workflow-id: Workflow identifier (keyword)
   - version: Version string (e.g., \"2.0.0\" or \"latest\")

   Returns workflow config map or nil if not found."
  [workflow-id version]
  (let [;; Try versioned filename first: workflows/standard-sdlc-v2.0.0.edn
        versioned-path (str "workflows/" (name workflow-id) "-v" version ".edn")
        ;; Fallback to non-versioned: workflows/standard-sdlc.edn
        base-path (str "workflows/" (name workflow-id) ".edn")
        ;; Try versioned first, then base, then scan for latest version
        resource-path (or (when (and version (not (latest? version)))
                           (when (io/resource versioned-path)
                             versioned-path))
                         (when (io/resource base-path)
                           base-path)
                         (find-latest-versioned-resource workflow-id))]
    (when-let [resource (when resource-path (io/resource resource-path))]
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

(defn try-load-from-cache
  "Try loading workflow from cache.

   Returns workflow or nil if not cached or skip-cache requested."
  [cache-key skip-cache?]
  (when-not skip-cache?
    (get @workflow-cache cache-key)))

(defn try-load-from-sources
  "Try loading workflow from resource or store.

   Returns workflow or nil if not found."
  [workflow-id version opts]
  (or (load-from-resource workflow-id version)
      (load-from-store workflow-id version opts)))

(defn validate-and-cache-workflow
  "Validate workflow and add to cache.

   Throws ex-info if validation fails.
   Returns result map with workflow, source, and validation."
  [workflow workflow-id version skip-validation?]
  (let [validation (if skip-validation?
                    {:valid? true :errors []}
                    (validator/validate-workflow workflow))]
    (when-not (:valid? validation)
      (throw (ex-info "Workflow validation failed"
                      {:workflow-id workflow-id
                       :version version
                       :errors (:errors validation)})))

    ;; Cache the validated workflow
    (swap! workflow-cache assoc [workflow-id version] workflow)

    {:workflow workflow
     :source (if (load-from-resource workflow-id version) :resource :store)
     :validation validation}))

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

    ;; Try cache first
    (if-let [cached (try-load-from-cache cache-key skip-cache?)]
      {:workflow cached
       :source :cache
       :validation {:valid? true :errors []}}

      ;; Try loading from sources
      (if-let [workflow (try-load-from-sources workflow-id version opts)]
        (validate-and-cache-workflow workflow workflow-id version skip-validation?)

        ;; Not found anywhere
        (throw (ex-info "Workflow not found"
                        {:workflow-id workflow-id
                         :version version}))))))

;------------------------------------------------------------------------------ Layer 4
;; Workflow discovery

(defn list-available-workflows
  "List available workflows from resources.
   Works for both filesystem and JAR resources.

   Returns vector of workflow metadata maps:
   [{:workflow/id keyword
     :workflow/version string
     :workflow/type keyword
     :workflow/description string}]"
  []
  (let [filenames (list-resource-names "workflows")]
    (if (seq filenames)
      (->> filenames
           (filter #(str/ends-with? % ".edn"))
           (map #(str "workflows/" %))
           (map (fn [resource-path]
                  (try
                    (when-let [url (io/resource resource-path)]
                      (with-open [rdr (io/reader url)]
                        (let [config (edn/read (java.io.PushbackReader. rdr))]
                          (select-keys config [:workflow/id
                                               :workflow/version
                                               :workflow/type
                                               :workflow/description
                                               :workflow/metadata]))))
                    (catch Exception _e
                      nil))))
           (filter some?)
           vec)
      [])))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load workflow from resource
  (def workflow-result
    (load-workflow :financial-etl "1.0.0" {}))

  (:source workflow-result)
  (:workflow workflow-result)
  (:validation workflow-result)

  ;; List available workflows
  (list-available-workflows)

  ;; Clear cache
  (clear-cache!)

  ;; Test cache behavior
  (def result1 (load-workflow :simple-v2 "2.0.0" {}))
  (def result2 (load-workflow :simple-v2 "2.0.0" {}))
  (= :resource (:source result1))
  (= :cache (:source result2))

  ;; Skip cache
  (def result3 (load-workflow :simple-v2 "2.0.0" {:skip-cache? true}))
  (= :resource (:source result3))

  :end)
