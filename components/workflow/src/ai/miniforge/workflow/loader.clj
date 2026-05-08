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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response.interface :as response]
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
   Supports two naming conventions:
   - <workflow-id>-v<semver>.edn  (e.g. standard-sdlc-v2.0.0.edn for :standard-sdlc)
   - <workflow-id>.<semver>.edn   (e.g. lean-sdlc-v1.0.0.edn for :lean-sdlc-v1)
   Returns the classpath-relative path for the highest-sorted match."
  [workflow-id]
  (let [id-str       (name workflow-id)
        prefix-dash  (str id-str "-v")
        prefix-dot   (str id-str ".")
        candidates   (->> (list-resource-names "workflows")
                          (filter #(str/ends-with? % ".edn"))
                          (filter #(or (str/starts-with? % prefix-dash)
                                       (str/starts-with? % prefix-dot)))
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

   Returns:
   - workflow config map on success
   - nil when no matching resource exists on the classpath
   - a `:fault` anomaly when a candidate resource was found but EDN
     parsing failed (carrying `:workflow-id`, `:resource-path`, `:error`)

   This is the canonical, anomaly-returning entry point. The boundary
   site `load-workflow` inlines a `response/throw-anomaly!` when an
   anomaly is observed, preserving the legacy thrown-exception contract
   for external callers that depend on it."
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
          (anomaly/anomaly :fault
                           "Failed to load workflow from resource"
                           {:workflow-id workflow-id
                            :resource-path resource-path
                            :error (.getMessage e)}))))))

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

   Returns:
   - `{:workflow w :source :resource}` when found via `load-from-resource`
   - `{:workflow w :source :store}` when found via `load-from-store`
   - nil if no matching resource or store entry was found
   - a `:fault` anomaly when a resource was found but failed to parse
     (propagates the anomaly from `load-from-resource`)

   The result includes `:source` so the caller can thread it through
   `validate-and-cache-workflow` without re-reading the resource."
  [workflow-id version opts]
  (let [from-resource (load-from-resource workflow-id version)]
    (cond
      (anomaly/anomaly? from-resource) from-resource
      (some? from-resource)            {:workflow from-resource :source :resource}
      :else                            (when-let [w (load-from-store workflow-id version opts)]
                                         {:workflow w :source :store}))))

(defn validate-and-cache-workflow
  "Validate workflow and add to cache.

   Arguments:
   - workflow         — the workflow config map
   - workflow-id      — keyword id
   - version          — version string
   - source           — `:resource` | `:store` (where the workflow was
                        loaded from). Threaded by callers that already
                        know — e.g. `try-load-from-sources` returns it
                        in its result map. Avoids a second
                        `load-from-resource` round-trip just to compute
                        `:source`.
   - skip-validation? — bypass the validator (still caches)

   Returns:
   - on success: `{:workflow :source :validation}`
   - on validation failure: an `:invalid-input` anomaly carrying
     `:workflow-id`, `:version`, `:errors`

   This is the canonical, anomaly-returning entry point. The boundary
   site `load-workflow` inlines a `response/throw-anomaly!` when an
   anomaly is observed."
  [workflow workflow-id version source skip-validation?]
  (let [validation (if skip-validation?
                    {:valid? true :errors []}
                    (validator/validate-workflow workflow))]
    (if-not (:valid? validation)
      (anomaly/anomaly :invalid-input
                       "Workflow validation failed"
                       {:workflow-id workflow-id
                        :version version
                        :errors (:errors validation)})
      (do
        ;; Cache the validated workflow
        (swap! workflow-cache assoc [workflow-id version] workflow)
        {:workflow workflow
         :source source
         :validation validation}))))

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

   This is the external-boundary entry point. It inlines
   `response/throw-anomaly!` on:
   - parse failures from `load-from-resource` (anomaly category
     `:anomalies/fault`)
   - validation failures from `validate-and-cache-workflow`
     (anomaly category `:anomalies.workflow/invalid-config`)
   - missing workflow on every source (anomaly category
     `:anomalies/not-found`)

   For anomaly-returning equivalents that callers can branch on as
   data, use `load-from-resource` and `validate-and-cache-workflow`
   directly."
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
      (let [from-sources (try-load-from-sources workflow-id version opts)]
        (cond
          ;; Source-level fault — escalate at this single boundary site.
          (anomaly/anomaly? from-sources)
          (response/throw-anomaly! :anomalies/fault
                                   (:anomaly/message from-sources)
                                   (:anomaly/data from-sources))

          ;; Found — validate-and-cache may itself return an anomaly.
          ;; `from-sources` is `{:workflow :source}` per
          ;; `try-load-from-sources`; pass `:source` through directly so
          ;; we don't re-read the resource.
          (some? from-sources)
          (let [{:keys [workflow source]} from-sources
                result (validate-and-cache-workflow workflow workflow-id version
                                                    source skip-validation?)]
            (if (anomaly/anomaly? result)
              (response/throw-anomaly! :anomalies.workflow/invalid-config
                                       (:anomaly/message result)
                                       (:anomaly/data result))
              result))

          ;; Not found anywhere
          :else
          (response/throw-anomaly! :anomalies/not-found
                                   "Workflow not found"
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
