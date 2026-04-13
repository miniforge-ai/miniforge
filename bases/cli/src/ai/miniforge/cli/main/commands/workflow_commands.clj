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

(ns ai.miniforge.cli.main.commands.workflow-commands
  "Workflow subcommands: execute (spec file), status, cancel.

   Distinct from 'workflow run' (which takes a registered workflow-id) —
   'workflow execute' accepts a spec file path and routes through the full
   run pipeline, identical to `miniforge run <spec>`."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.main.commands.run :as cmd-run]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- read-event-lines
  "Read all non-blank EDN lines from a workflow event file."
  [event-file]
  (try
    (->> (slurp event-file)
         str/split-lines
         (remove str/blank?)
         (keep #(try (edn/read-string %) (catch Exception _ nil))))
    (catch Exception _ [])))

(defn- derive-status
  "Derive a workflow status label from its event stream."
  [events]
  (let [by-type (group-by :event/type events)]
    (cond
      (seq (get by-type :workflow/completed)) "completed"
      (seq (get by-type :workflow/failed))    "failed"
      (seq (get by-type :workflow/started))   "running"
      :else                                   "unknown")))

(defn- colorize-status [s]
  (case s
    "completed" (display/style s :foreground :green)
    "failed"    (display/style s :foreground :red)
    (display/style s :foreground :yellow)))

;------------------------------------------------------------------------------ Layer 1
;; Command implementations

(defn workflow-execute-cmd
  "Execute a workflow from a spec file (alias for `run <spec>`).

   Routes through the same pipeline as `miniforge run <spec>`:
   parses the spec, validates, selects workflow type, and executes."
  [opts]
  (let [{:keys [spec]} opts]
    (if-not spec
      (do (display/print-error
           (str "Usage: " (app-config/command-string "workflow execute <spec-file>")))
          (System/exit 1))
      (cmd-run/run-cmd opts))))

(defn workflow-status-cmd
  "Show the status of a workflow by ID.

   Reads the workflow's event file from the events directory and
   reconstructs the current state from the event stream."
  [opts]
  (let [{:keys [id]} opts]
    (if-not id
      (do (display/print-error
           (str "Usage: " (app-config/command-string "workflow status <id>")))
          (System/exit 1))
      (let [events-dir (app-config/events-dir)
            event-file (str events-dir "/" id ".edn")]
        (if-not (fs/exists? event-file)
          (do (display/print-error (str "No workflow found with ID: " id))
              (System/exit 1))
          (let [events  (read-event-lines event-file)
                status  (derive-status events)
                started (first (filter #(= :workflow/started (:event/type %)) events))
                failed  (first (filter #(= :workflow/failed (:event/type %)) events))
                phases  (->> events
                             (filter #(= :workflow/phase-completed (:event/type %)))
                             (mapv #(select-keys % [:workflow/phase :phase/outcome :phase/duration-ms])))]
            (println)
            (println (display/style (str "Workflow: " id) :foreground :cyan :bold true))
            (println (str "  Status:  " (colorize-status status)))
            (when-let [ts (:event/timestamp started)]
              (println (str "  Started: " ts)))
            (when (seq phases)
              (println "  Phases:")
              (doseq [{phase :workflow/phase outcome :phase/outcome dur :phase/duration-ms} phases]
                (let [ok? (= :success outcome)]
                  (println (str "    "
                                (display/style (if ok? "✓" "✗") :foreground (if ok? :green :red))
                                " " (name phase)
                                (when dur (str "  (" dur "ms)")) )))))
            (when failed
              (println (str "  Error:   "
                            (get failed :workflow/failure-reason "unknown"))))
            (println)))))))

(defn workflow-cancel-cmd
  "Cancel a running workflow by writing a stop command file.

   The CLI command poller (started with each workflow) reads this file
   and calls event-stream/cancel! on the control state."
  [opts]
  (let [{:keys [id]} opts]
    (if-not id
      (do (display/print-error
           (str "Usage: " (app-config/command-string "workflow cancel <id>")))
          (System/exit 1))
      (let [commands-dir (app-config/commands-dir (str id))
            cmd-file     (str commands-dir "/cancel-" (System/currentTimeMillis) ".edn")]
        (try
          (fs/create-dirs commands-dir)
          (spit cmd-file (pr-str {:command "stop"
                                  :workflow-id (str id)
                                  :timestamp (java.util.Date.)}))
          (display/print-success (str "Cancel signal sent to workflow: " id))
          (catch Exception e
            (display/print-error (str "Failed to cancel workflow: " (ex-message e)))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test workflow status
  (workflow-status-cmd {:id "some-uuid"})

  ;; Test workflow cancel
  (workflow-cancel-cmd {:id "some-uuid"})

  :end)
