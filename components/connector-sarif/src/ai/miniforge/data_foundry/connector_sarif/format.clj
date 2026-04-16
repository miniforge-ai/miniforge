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

(ns ai.miniforge.data-foundry.connector-sarif.format
  "SARIF JSON and CSV parsing helpers.
   Handles SARIF v2.1.0 structure (tool -> runs[] -> results[] -> locations[]).
   Provides pluggable CSV parsing with configurable column mapping."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.csv :as csv]))

;; --------------------------------------------------------------------------
;; SARIF v2.1.0 Parsing

(defn- normalize-severity
  "Normalize SARIF level string to keyword."
  [level]
  (case (str/lower-case (or level "warning"))
    "error"   :error
    "warning" :warning
    "note"    :note
    "none"    :none
    :warning))

(defn- extract-location
  "Extract physical location from a SARIF location object."
  [loc]
  (let [phys (get loc "physicalLocation")
        artifact (get phys "artifactLocation")
        region (get phys "region")]
    {:file   (get artifact "uri")
     :line   (get region "startLine")
     :column (get region "startColumn")}))

(defn- parse-sarif-result
  "Parse a single SARIF result into a unified violation record."
  [result tool-name run-idx result-idx]
  (let [rule-id  (get result "ruleId" "unknown")
        message  (get-in result ["message" "text"] "")
        level    (get result "level" "warning")
        locs     (get result "locations" [])
        location (if (seq locs) (extract-location (first locs)) {})]
    {:violation/id          (str tool-name ":" run-idx ":" result-idx)
     :violation/rule-id     rule-id
     :violation/message     message
     :violation/severity    (normalize-severity level)
     :violation/location    location
     :violation/source-tool tool-name
     :violation/raw         result}))

(defn parse-sarif
  "Parse a SARIF v2.1.0 JSON file into violation records.
   Returns a vector of unified SarifViolation maps."
  [path]
  (let [content (json/parse-string (slurp path))
        runs    (get content "runs" [])]
    (into []
          (mapcat
           (fn [[run-idx run]]
             (let [tool-name (get-in run ["tool" "driver" "name"] "unknown")
                   results   (get run "results" [])]
               (map-indexed
                (fn [res-idx result]
                  (parse-sarif-result result tool-name run-idx res-idx))
                results)))
           (map-indexed vector runs)))))

;; --------------------------------------------------------------------------
;; CSV Parsing

(def default-csv-columns
  "Default column mapping for CSV scan output."
  {:rule-id  "Rule ID"
   :message  "Message"
   :severity "Severity"
   :file     "File"
   :line     "Line"
   :column   "Column"})

(defn- find-column-index
  "Find column index by header name (case-insensitive)."
  [headers col-name]
  (let [target (str/lower-case col-name)]
    (first (keep-indexed
            (fn [idx h] (when (= (str/lower-case (str/trim h)) target) idx))
            headers))))

(defn- csv-row->violation
  "Convert a CSV row to a unified violation record."
  [row headers col-map idx]
  (let [get-col (fn [k]
                  (when-let [col-idx (find-column-index headers (get col-map k ""))]
                    (nth row col-idx nil)))]
    {:violation/id          (str "csv:" idx)
     :violation/rule-id     (or (get-col :rule-id) "unknown")
     :violation/message     (or (get-col :message) "")
     :violation/severity    (normalize-severity (get-col :severity))
     :violation/location    {:file   (get-col :file)
                             :line   (when-let [l (get-col :line)]
                                       (try (Integer/parseInt (str/trim l))
                                            (catch Exception _ nil)))
                             :column (when-let [c (get-col :column)]
                                       (try (Integer/parseInt (str/trim c))
                                            (catch Exception _ nil)))}
     :violation/source-tool "csv-import"
     :violation/raw         (zipmap headers row)}))

(defn parse-csv
  "Parse a CSV scan output file into violation records.
   Accepts optional column mapping override."
  ([path] (parse-csv path nil))
  ([path col-map]
   (let [col-map (merge default-csv-columns col-map)]
     (with-open [reader (io/reader path)]
       (let [rows    (doall (csv/read-csv reader))
             headers (first rows)
             data    (rest rows)]
         (vec (map-indexed
               (fn [idx row] (csv-row->violation row headers col-map idx))
               data)))))))

;; --------------------------------------------------------------------------
;; File discovery and format detection

(defn detect-format
  "Detect file format from extension."
  [path]
  (cond
    (str/ends-with? (str/lower-case path) ".sarif")     :sarif
    (str/ends-with? (str/lower-case path) ".sarif.json") :sarif
    (str/ends-with? (str/lower-case path) ".csv")        :csv
    :else :unknown))

(defn list-scan-files
  "List scannable files in a path (file or directory)."
  [source-path]
  (let [f (io/file source-path)]
    (cond
      (not (.exists f)) []
      (.isFile f)       [(.getAbsolutePath f)]
      (.isDirectory f)  (->> (.listFiles f)
                             (filter #(#{:sarif :csv} (detect-format (.getName %))))
                             (mapv #(.getAbsolutePath %)))
      :else [])))

(defn parse-file
  "Parse a single file based on format. Returns violation records."
  [path fmt csv-columns]
  (let [actual-fmt (if (= fmt :auto) (detect-format path) (or fmt (detect-format path)))]
    (case actual-fmt
      :sarif (parse-sarif path)
      :csv   (parse-csv path csv-columns)
      (throw (ex-info (str "Unsupported format: " actual-fmt) {:path path :format actual-fmt})))))
