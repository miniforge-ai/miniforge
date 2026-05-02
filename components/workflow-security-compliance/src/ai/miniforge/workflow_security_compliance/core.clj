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

(ns ai.miniforge.workflow-security-compliance.core
  "Pure workflow helpers for the security-compliance pipeline."
  (:require
   [ai.miniforge.coerce.interface :as coerce]
   [ai.miniforge.workflow-security-compliance.config :as config]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(defn- parse-int
  [value]
  (coerce/safe-parse-int (str/trim (str value)) 0))

(defn- csv-columns
  [line]
  (str/split line #"," 6))

(defn- csv-severity
  [severity]
  (-> severity
      str
      str/trim
      str/lower-case
      keyword))

(defn- sarif-location
  [result]
  (let [location (first (get result "locations" []))
        physical-location (get location "physicalLocation")
        artifact-location (get physical-location "artifactLocation")
        region (get physical-location "region")
        file (get artifact-location "uri")
        line (get region "startLine")
        column (get region "startColumn")]
    {:file file
     :line line
     :column column}))

(defn- sarif-violation
  [tool-name run-idx result-idx result]
  (let [violation-id (str tool-name ":" run-idx ":" result-idx)
        rule-id (get result "ruleId" "unknown")
        message (get-in result ["message" "text"] "")
        severity (keyword (get result "level" "warning"))
        location (sarif-location result)]
    {:violation/id violation-id
     :violation/rule-id rule-id
     :violation/message message
     :violation/severity severity
     :violation/location location
     :violation/source-tool tool-name
     :violation/raw result}))

(defn- csv-violation
  [rule-id message severity file line-no column]
  (let [trimmed-rule-id (str/trim (str rule-id))
        sanitized-message (-> message
                              str
                              (str/replace #"\"" "")
                              str/trim)
        location {:file (str/trim (str file))
                  :line (parse-int line-no)
                  :column (parse-int column)}
        violation-id (str "csv:" trimmed-rule-id ":" (:line location))]
    {:violation/id violation-id
     :violation/rule-id trimmed-rule-id
     :violation/message sanitized-message
     :violation/severity (csv-severity severity)
     :violation/location location
     :violation/source-tool "csv-import"
     :violation/raw {:line (str rule-id "," message "," severity "," file "," line-no "," column)}}))

(defn- trace-api
  [violation]
  (let [message (get violation :violation/message "")
        matched-api (second (re-find (config/trace-api-pattern) message))]
    (if (seq matched-api)
      matched-api
      (get violation :violation/rule-id))))

(defn- dynamic-load?
  [violation]
  (boolean (re-find (config/dynamic-load-pattern)
                    (get violation :violation/message ""))))

(defn- traced-violation
  [violation]
  (let [actual-api (trace-api violation)
        file (get-in violation [:violation/location :file] "unknown")
        dynamic-load-flag (dynamic-load? violation)]
    (assoc violation
           :trace/actual-api actual-api
           :trace/call-chain [file]
           :trace/dynamic-load? dynamic-load-flag)))

(defn- documented-api?
  [violation known-apis]
  (let [actual-api (get violation :trace/actual-api "")]
    (boolean (some #(str/includes? actual-api %) known-apis))))

(defn- verified-violation
  [violation known-apis]
  (let [documented? (documented-api? violation known-apis)
        verified-by (if documented?
                      :known-apis-registry
                      :stub-agent)]
    (assoc violation
           :verified/documented? documented?
           :verified/public? documented?
           :verified/by verified-by)))

(defn- documented-static?
  [violation]
  (and (get violation :verified/documented? false)
       (not (get violation :trace/dynamic-load? false))))

(defn- undocumented-error?
  [violation]
  (and (not (get violation :verified/documented? false))
       (= :error (get violation :violation/severity))))

(defn- classification-scenario
  [violation]
  (cond
    (documented-static? violation) :documented-static
    (undocumented-error? violation) :undocumented-error
    :else :manual-review))

(defn- classification-category
  [scenario]
  (get {:documented-static :false-positive
        :undocumented-error :true-positive
        :manual-review :needs-investigation}
       scenario
       :needs-investigation))

(defn- classified-violation
  [violation]
  (let [scenario (classification-scenario violation)
        category (classification-category scenario)
        confidence (config/classification-confidence scenario)
        documented? (get violation :verified/documented? false)
        doc-status (if documented? :documented :undocumented)
        reason (config/classification-reason scenario)]
    (assoc violation
           :classification/category category
           :classification/confidence confidence
           :classification/doc-status doc-status
           :classification/reason reason)))

(defn- exclusion-entry
  [violation]
  (let [file (get-in violation [:violation/location :file])
        line (get-in violation [:violation/location :line])
        classification (get violation :classification/category)
        justification (get violation :classification/reason)
        api (get violation :trace/actual-api)
        documented? (get violation :verified/documented? false)]
    {:file file
     :line line
     :classification classification
     :justification justification
     :api api
     :documented? documented?}))

(defn- add-exclusion
  [acc violation]
  (update acc
          (get violation :violation/rule-id)
          (fnil conj [])
          (exclusion-entry violation)))

(defn- exclusion-edn
  [generated-at exclusions excluded-count flagged-count]
  {:generated-at generated-at
   :exclusions exclusions
   :summary {:total-excluded excluded-count
             :total-flagged flagged-count}})

(defn- sarif-run-violations
  "Return a seq of violations extracted from a single SARIF run at `run-idx`.
   Indexed so each violation's id encodes its tool/run/result position."
  [[run-idx run]]
  (let [tool-name (get-in run ["tool" "driver" "name"] "unknown")
        results  (get run "results" [])]
    (map-indexed
     (partial sarif-violation tool-name run-idx)
     results)))

;------------------------------------------------------------------------------ Layer 1
;; Pipelines

(defn parse-sarif-file
  [path]
  (let [content (-> path slurp (json/parse-string))
        runs    (get content "runs" [])]
    (into [] (mapcat sarif-run-violations) (map-indexed vector runs))))

(defn parse-csv-file
  [path]
  (let [lines (str/split (slurp path) #"\n")
        data-lines (remove str/blank? (rest lines))]
    (mapv
     (fn [line]
       (let [[rule-id message severity file line-no column] (csv-columns line)]
         (csv-violation rule-id message severity file line-no column)))
     data-lines)))

(defn trace-violations
  [violations]
  (mapv traced-violation violations))

(defn verify-violations
  [violations known-apis]
  (mapv #(verified-violation % known-apis) violations))

(defn classify-violations
  [violations]
  (mapv classified-violation violations))

(defn build-exclusion-output
  [classified]
  (let [excluded (filterv #(= :false-positive (get % :classification/category)) classified)
        flagged (filterv #(not= :false-positive (get % :classification/category)) classified)
        exclusion-list (reduce add-exclusion {} excluded)
        excluded-count (count excluded)
        flagged-count (count flagged)]
    {:exclusion-list exclusion-list
     :total-excluded excluded-count
     :total-flagged flagged-count}))

;------------------------------------------------------------------------------ Layer 2
;; I/O boundaries

(defn resolve-scan-path
  [path]
  (let [file (io/file path)]
    (cond
      (.exists file)
      (.getPath file)

      :else
      (some-> (or (io/resource path)
                  (io/resource (str/replace-first path #"^test/" ""))
                  (io/resource (str/replace-first path #"^test/fixtures/" "fixtures/")))
              io/file
              .getPath))))

(defn parse-scan-path
  [path]
  (let [resolved-path (or (resolve-scan-path path) path)]
    (if (.exists (io/file resolved-path))
      (cond
        (str/ends-with? resolved-path ".sarif") (parse-sarif-file resolved-path)
        (str/ends-with? resolved-path ".csv") (parse-csv-file resolved-path)
        :else [])
      [])))

(defn parse-scan-paths
  [scan-paths]
  (into [] (mapcat parse-scan-path) scan-paths))

(defn write-exclusions!
  [output-dir exclusion-output]
  (let [directory-name (config/output-directory-name)
        file-name (config/output-file-name)
        exclusion-dir (io/file output-dir directory-name)
        exclusion-file (io/file exclusion-dir file-name)
        generated-at (str (java.time.Instant/now))
        exclusion-data (exclusion-edn generated-at
                                      (get exclusion-output :exclusion-list {})
                                      (get exclusion-output :total-excluded 0)
                                      (get exclusion-output :total-flagged 0))]
    (.mkdirs exclusion-dir)
    (spit exclusion-file (pr-str exclusion-data))
    (.getAbsolutePath exclusion-file)))
