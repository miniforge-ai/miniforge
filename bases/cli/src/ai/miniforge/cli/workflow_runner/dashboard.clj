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

(ns ai.miniforge.cli.workflow-runner.dashboard
  "Dashboard auto-discovery and status display.

   Architecture: Zero network communication between CLI and dashboard.
   - Events (CLI -> Dashboard): CLI writes to the app-configured events directory via file sink.
     Dashboard tail-follows the files via watcher.
   - Commands (Dashboard -> CLI): the dashboard writes
     `:supervisory/intervention-requested` events into
     `{events-dir}/operator/`; the runner's operator-event consumer
     (`workflow-runner.control`) routes them through the intervention
     lifecycle. The pre-D-4 `.edn` command-file poller that lived here
     was an ungoverned second channel and has been deleted, not kept
     alongside."
  (:require
   [cheshire.core :as json]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.workflow-runner.display :as display]))

;------------------------------------------------------------------------------ Layer 0
;; Auto-discovery

(defn pid-alive?
  "Check if a process with the given PID is still running."
  [pid]
  (try
    (when pid
      (.isPresent (java.lang.ProcessHandle/of (long pid))))
    (catch Exception _ false)))

(defn discover-dashboard-url
  "Auto-discover running dashboard from the app-configured dashboard port file.
   Returns dashboard URL string or nil. Verifies the dashboard PID is alive
   and cleans up stale discovery files from crashed processes."
  []
  (try
    (let [discovery-file (java.io.File. (app-config/dashboard-port-file))]
      (when (.exists discovery-file)
        (let [info (json/parse-string (slurp discovery-file) true)
              port (:port info)
              pid (:pid info)]
          (if (pid-alive? pid)
            (str "http://localhost:" port)
            ;; Stale file from crashed dashboard — clean up
            (do (.delete discovery-file) nil)))))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1
;; Status display

(defn print-dashboard-status!
  "Print dashboard URL (if discovered) and events directory path."
  [quiet]
  (when-not quiet
    (let [events-dir (app-config/events-dir)]
      (if-let [url (discover-dashboard-url)]
        (println (display/colorize :cyan (str "   Dashboard: " url)))
        (println (display/colorize :cyan
                                   (str "   Dashboard: not running (start with `"
                                        (app-config/command-string "web")
                                        "`)"))))
      (println (display/colorize :cyan (str "   Events: " events-dir))))))
