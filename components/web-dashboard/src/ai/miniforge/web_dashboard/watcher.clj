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
(ns ai.miniforge.web-dashboard.watcher
  "Follows the file sink's live/ and archived/ workflow directories and
   publishes each new event, through event-stream's layout tail, the one
   the standalone TUI uses too. Runs active at startup are read in full;
   older files are left to the archive scan and history."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.web-dashboard.config :as dashboard-config]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} parse-edn-line
  "Parse a single EDN line, returning nil on parse failure."
  [line]
  (try
    (when (and line (not= "" (.trim ^String line)))
      (edn/read-string line))
    (catch Exception _
      nil)))

(def ^{:stratum 0} active-window-ms
  "A directory modified within this window is active."
  (:stale-threshold-ms dashboard-config/defaults))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} start-watcher!
  "Start a daemon thread that follows `events-dir` and calls
   `publish-fn` with each new event (`es/start-layout-tail!`). Returns a
   function that stops it."
  [events-dir publish-fn]
  (es/start-layout-tail! events-dir publish-fn
                         {:window-ms   active-window-ms
                          :thread-name "miniforge-event-watcher"}))
