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

(ns ai.miniforge.connector-file.reader
  "File reading for CSV, JSON, and EDN formats."
  (:require [clojure.data.csv :as csv]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn read-csv
  "Read CSV file, returning vector of maps keyed by header row."
  [path]
  (with-open [rdr (io/reader path)]
    (let [data (csv/read-csv rdr)
          headers (map keyword (first data))
          rows (rest data)]
      (mapv #(zipmap headers %) rows))))

(defn read-json
  "Read JSON file, returning vector of maps."
  [path]
  (with-open [rdr (io/reader path)]
    (let [data (json/parse-stream rdr true)]
      (if (sequential? data) (vec data) [data]))))

(defn read-edn
  "Read EDN file, returning vector of maps."
  [path]
  (let [data (edn/read-string (slurp path))]
    (if (vector? data) data [data])))

(defn read-file
  "Read file in the given format. Returns vector of record maps."
  [path format]
  (case format
    :csv  (read-csv path)
    :json (read-json path)
    :edn  (read-edn path)))
