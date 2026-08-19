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
(ns ai.miniforge.cli.observability.io
  "Log/event file path resolution, directory discovery, and log-line
   parsing. Split out of `ai.miniforge.cli.observability` (rule 210: the
   combined namespace measured 7 real layers, max 3) — same approach as
   the policy-pack loader split, miniforge#1772, and detection split,
   miniforge#1761/#1773.

   Layer 0: workflow-id to path resolution, directory listing, and EDN
     log-line parsing — pure, no same-file dependents."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]))

;------------------------------------------------------------------------------ Layer 0

;;------------------------------------------------------------------------------ File Discovery
(defn ^{:stratum 0} event-file-path
  "Get path to event file for a workflow.

   Arguments:
     workflow-id - UUID or string workflow identifier

   Returns: String path to the active CLI app events directory."
  [workflow-id]
  (let [events-dir (io/file (app-config/events-dir))
        event-file (str workflow-id ".edn")]
    (.getPath (io/file events-dir event-file))))

(defn ^{:stratum 0} log-file-path
  "Get path to log file for a workflow.

   Arguments:
     workflow-id - UUID or string workflow identifier

   Returns: String path to the active CLI app logs directory."
  [workflow-id]
  (let [logs-dir (io/file (app-config/logs-dir))
        log-file (str workflow-id ".log")]
    (.getPath (io/file logs-dir log-file))))

(defn ^{:stratum 0} find-log-files
  "Find all log files in the active CLI app logs directory.

   Returns: Vector of file paths sorted by modification time (newest first)"
  []
  (let [log-dir (io/file (app-config/logs-dir))]
    (if (.exists log-dir)
      (->> (.listFiles log-dir)
           (filter #(.isFile %))
           (filter #(.endsWith (.getName %) ".log"))
           (sort-by #(.lastModified %) >)
           (mapv #(.getAbsolutePath %)))
      [])))

(defn ^{:stratum 0} find-event-stream-files
  "Find event stream files (workflow execution events).

   Returns: Vector of file paths sorted by modification time (newest first)"
  []
  (let [event-dir (io/file (app-config/events-dir))]
    (if (.exists event-dir)
      (->> (.listFiles event-dir)
           (filter #(.isFile %))
           (filter #(.endsWith (.getName %) ".edn"))
           (sort-by #(.lastModified %) >)
           (mapv #(.getAbsolutePath %)))
      [])))

;; Log Parsing
(defn ^{:stratum 0} parse-log-line
  "Parse a single log line (EDN format).

   Arguments:
     line - String log line

   Returns: Parsed map or nil if parse fails"
  [line]
  (when-not (str/blank? line)
    (try
      (edn/read-string line)
      (catch Exception _
        nil))))
