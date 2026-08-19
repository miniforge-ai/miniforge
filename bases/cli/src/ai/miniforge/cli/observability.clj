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
(ns ai.miniforge.cli.observability
  "CLI commands for logs, events, and workflow observability.

   Provides kubectl-style commands with per-workflow streams:
   - logs tail <workflow-id>  - Tail logs for specific workflow
   - logs tail --all          - Tail all workflow logs (aggregated)
   - events tail <workflow-id> - Tail events for specific workflow
   - events tail --all         - Tail all workflow events (aggregated)

   Layer 0: tail-logs, tail-events (over the observability.io /
     observability.formatting / observability.tailing sibling namespaces)
   Layer 1: logs-command, events-command (over Layer 0)
   Layer 2: handle-logs, handle-events — public API (over Layer 1)

   3 real strata, within the rule 210 budget. PR 2/2 of this train:
   file path resolution and EDN log-line parsing moved to
   `observability.io`, ANSI-color/entry formatting to
   `observability.formatting`, and (in this PR) the workflow-timeline
   (`workflow-events-dir`, `strip-transit-prefix`, `ts-short`,
   `summarize-event`, `read-workflow-events`, `show-events`) and stream-
   tailing (`show-last-n-lines`, `tail-file`, `print-stream-header`,
   `list-files-command`, `cat-file-command`, `tail-stream-file`)
   primitives to `observability.timeline` and `observability.tailing`
   respectively — bringing this namespace from 7 real layers down to 3.
   Same approach as the policy-pack loader split, miniforge#1772, and
   detection split, miniforge#1761/#1773."
  (:require
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.observability.formatting :as formatting]
   [ai.miniforge.cli.observability.io :as observability-io]
   [ai.miniforge.cli.observability.tailing :as tailing]
   [ai.miniforge.cli.observability.timeline :as timeline]
   [ai.miniforge.logging.interface :as logging]))

;------------------------------------------------------------------------------ Layer 0

;; Stream Tailing
(defn ^{:stratum 0} tail-logs
  "Tail MiniForge logs.

   Options:
     :workflow-id - Specific workflow to tail (UUID or string)
     :all - Tail all workflows (aggregated)
     :file - Specific log file to tail (overrides workflow-id)
     :lines - Number of initial lines to show (default: 10)
     :follow - Whether to follow (tail -f) (default: true)"
  [& [{:keys [workflow-id all file lines follow] :or {lines 10 follow true}}]]
  (let [log-files (observability-io/find-log-files)
        target-file (cond
                      file file
                      workflow-id (observability-io/log-file-path workflow-id)
                      :else (first log-files))]
    (cond
      ;; Tail all workflows
      all
      (do
        (println (formatting/colorize :cyan (messages/t :observability/tailing-all-logs {:icon "📋"})))
        (println (formatting/colorize :gray (apply str (repeat 80 "─"))))
        (println (formatting/colorize :yellow (messages/t :observability/aggregated-not-implemented))))

      ;; Tail specific workflow
      target-file
      (tailing/tail-stream-file {:file-path target-file
                         :parse-fn observability-io/parse-log-line
                         :format-fn formatting/format-log-entry
                         :lines lines
                         :follow follow
                         :icon "📋"
                         :label "logs"})

      ;; No logs found
      :else
      (println (formatting/colorize :yellow (messages/t :observability/no-log-files {:dir (app-config/logs-dir)}))))))

(defn ^{:stratum 0} tail-events
  "Tail MiniForge workflow events in real-time.

   Options:
     :workflow-id - Specific workflow to tail (UUID or string)
     :all - Tail all workflows (aggregated)
     :file - Specific event file to tail (overrides workflow-id)
     :lines - Number of initial events to show (default: 20)
     :follow - Whether to follow (tail -f) (default: true)
     :filter - Event type filter (e.g., :agent/chunk)"
  [& [{:keys [workflow-id all file lines follow filter] :or {lines 20 follow true}}]]
  (let [event-files (observability-io/find-event-stream-files)
        target-file (cond
                      file file
                      workflow-id (observability-io/event-file-path workflow-id)
                      :else (first event-files))
        filter-fn (if filter
                    (fn [event] (= (:event/type event) filter))
                    (constantly true))
        extra-info (when filter (str "Filter: " filter))]
    (cond
      ;; Tail all workflows
      all
      (do
        (println (formatting/colorize :cyan (messages/t :observability/tailing-all-events {:icon "📊"})))
        (when filter
          (println (formatting/colorize :gray (str "Filter: " filter))))
        (println (formatting/colorize :gray (apply str (repeat 80 "─"))))
        (println (formatting/colorize :yellow (messages/t :observability/aggregated-not-implemented))))

      ;; Tail specific workflow
      target-file
      (tailing/tail-stream-file {:file-path target-file
                         :parse-fn observability-io/parse-log-line
                         :format-fn formatting/format-event
                         :filter-fn filter-fn
                         :lines lines
                         :follow follow
                         :icon "📊"
                         :label "events"
                         :extra-info extra-info})

      ;; No events found
      :else
      (println (formatting/colorize :yellow (messages/t :observability/no-event-files {:dir (app-config/events-dir)}))))))

;------------------------------------------------------------------------------ Layer 1

;; CLI Commands
(defn ^{:stratum 1} logs-command
  "Handle 'mf logs' command.

   Subcommands:
     tail [workflow-id] [options]  - Tail logs (default)
     list                          - List available log files
     cat <file>                    - Display log file
     cleanup                       - Clean up old rotated logs"
  [{:keys [subcommand workflow-id file lines follow all] :or {subcommand "tail" lines 10 follow true}}]
  (case subcommand
    "tail" (tail-logs {:workflow-id workflow-id :all all :file file :lines lines :follow follow})
    "list" (tailing/list-files-command observability-io/find-log-files "log files")
    "cat" (tailing/cat-file-command file)
    "cleanup" (let [logs-dir (app-config/logs-dir)
                    count (logging/cleanup-old-rotated-logs logs-dir 7)]
                (println (formatting/colorize :green (messages/t :observability/cleanup-result {:count count}))))
    (println (formatting/colorize :red (messages/t :observability/unknown-subcommand {:subcommand subcommand})))))

(defn ^{:stratum 1} events-command
  "Handle 'mf events' command.

   Subcommands:
     tail [workflow-id] [options]  - Tail events (default)
     list                          - List available event files
     cat <file>                    - Display event file
     show <workflow-id>            - Render a human-readable timeline for a workflow"
  [{:keys [subcommand workflow-id file lines follow filter all no-chunks no-status]
    :or {subcommand "tail" lines 20 follow true}}]
  (case subcommand
    "tail" (tail-events {:workflow-id workflow-id :all all :file file :lines lines :follow follow :filter filter})
    "list" (tailing/list-files-command observability-io/find-event-stream-files "event files")
    "cat" (tailing/cat-file-command file)
    "show" (timeline/show-events {:workflow-id workflow-id :filter filter
                         :no-chunks (if (nil? no-chunks) true no-chunks)
                         :no-status no-status})
    (println (formatting/colorize :red (messages/t :observability/unknown-subcommand {:subcommand subcommand})))))

;------------------------------------------------------------------------------ Layer 2

;;------------------------------------------------------------------------------ Public API
(defn ^{:stratum 2} handle-logs
  "Entry point for 'mf logs' command."
  [args]
  (logs-command args))

(defn ^{:stratum 2} handle-events
  "Entry point for 'mf events' command."
  [args]
  (events-command args))
