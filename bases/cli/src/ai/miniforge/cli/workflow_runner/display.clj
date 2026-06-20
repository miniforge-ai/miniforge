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

(ns ai.miniforge.cli.workflow-runner.display
  "Terminal output formatting for workflow execution."
  (:require
   [clojure.pprint]
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; ANSI color primitives

(def ansi-codes
  {:reset "\u001b[0m"
   :bold "\u001b[1m"
   :cyan "\u001b[36m"
   :green "\u001b[32m"
   :yellow "\u001b[33m"
   :red "\u001b[31m"})

(defn colorize [color text]
  (str (get ansi-codes color "") text (:reset ansi-codes)))

(defn format-duration [ms]
  (cond
    (< ms 1000) (str ms "ms")
    (< ms 60000) (format "%.1fs" (/ ms 1000.0))
    :else (format "%.1fm" (/ ms 60000.0))))

(defn- humanize-keyword
  [kw]
  (some-> kw name (str/replace "-" " ")))

(defn- dependency-display-name
  [event]
  (or (some-> (:dependency/vendor event) humanize-keyword str/capitalize)
      (let [dependency-id (:dependency/id event)]
        (cond
          (keyword? dependency-id) (str/capitalize (humanize-keyword dependency-id))
          (string? dependency-id) dependency-id
          dependency-id (str dependency-id)
          :else nil))
      "?"))

(defn- dependency-kind-label
  [event]
  (messages/t (keyword "workflow-runner.dependency-kind"
                       (name (or (:dependency/kind event) :environment)))))

(defn- dependency-status-label
  [event]
  (messages/t (keyword "workflow-runner.dependency-status"
                       (name (or (:dependency/status event) :healthy)))))

(defn- dependency-event-line
  [event message-key color]
  (colorize color
            (messages/t message-key
                        {:dependency (dependency-display-name event)
                         :kind (dependency-kind-label event)
                         :status (dependency-status-label event)})))

;------------------------------------------------------------------------------ Layer 1
;; Workflow progress output

(defn print-workflow-header [workflow-id version quiet?]
  (when-not quiet?
    (println (colorize :cyan (str "\n" (apply str (repeat 65 "━")))))
    (println (colorize :bold (messages/t :workflow-runner/header
                                         {:display-name (app-config/display-name)})))
    (println (colorize :cyan (messages/t :workflow-runner/workflow
                                         {:workflow-id (name workflow-id)})))
    (println (colorize :cyan (messages/t :workflow-runner/version
                                         {:version version})))
    (println (colorize :cyan (str (apply str (repeat 65 "━")) "\n")))
    (flush)))

(defn format-event-line
  "Format a concise progress line for a lifecycle event. Returns nil for unknown events."
  [event]
  (let [evt (:event/type event)
        phase (or (:workflow/phase event) (:phase event))
        gate (or (:gate/id event) (:gate event))
        agent (or (:agent/id event) (:agent event))
        tool-id (:tool/id event)]
    (case evt
      :workflow/started (colorize :cyan (messages/t :workflow-runner/start))
      :workflow/completed (str (colorize :green (messages/t :workflow-runner/completed))
                               (when-let [d (:workflow/duration-ms event)]
                                 (str " (" (format-duration d) ")")))
      :workflow/failed (str (colorize :red (messages/t :workflow-runner/failed))
                            (when-let [r (:workflow/failure-reason event)]
                              (str ": " r)))
      :workflow/phase-started (colorize :yellow (messages/t :workflow-runner/phase-started
                                                            {:phase phase}))
      :workflow/phase-completed (let [outcome (or (:phase/outcome event) :completed)
                                      color   (case outcome
                                                :failure    :red
                                                :blocked    :red
                                                :skipped    :yellow
                                                :redirected :yellow
                                                :green)
                                      symbol  (case outcome
                                                :failure    "✗"
                                                :blocked    "⊘"
                                                :skipped    "○"
                                                :redirected "↻"
                                                "✓")]
                                  (str (colorize color (messages/t :workflow-runner/phase-completed
                                                                   {:symbol symbol
                                                                    :phase phase
                                                                    :outcome (name outcome)}))
                                       (when-let [d (:phase/duration-ms event)]
                                         (str " (" (format-duration d) ")"))))
      :workflow/milestone-reached (colorize :green (messages/t :workflow-runner/milestone
                                                                {:message (:message event)}))
      :workspace/persisted (colorize :cyan (messages/t :workflow-runner/workspace-persisted
                                                       {:phase       (some-> (:workspace/phase event) name)
                                                        :bundle-path (or (:workspace/bundle-path event)
                                                                         (:workspace/commit-sha event)
                                                                         "(no archive path)")}))
      :agent/started (colorize :cyan (messages/t :workflow-runner/agent-started
                                                 {:agent agent}))
      :agent/completed (colorize :green (messages/t :workflow-runner/agent-completed
                                                    {:agent agent}))
      :agent/failed (colorize :red (messages/t :workflow-runner/agent-failed
                                               {:agent agent}))
      :agent/status (colorize :cyan (messages/t :workflow-runner/agent-status
                                                {:agent agent
                                                 :status (or (:message event)
                                                             (:status/type event)
                                                             (messages/t :workflow-runner/default-status))}))
      :tool/invoked (colorize :yellow (messages/t :workflow-runner/tool-invoked
                                                  {:tool-id tool-id}))
      :tool/completed (colorize :green (messages/t :workflow-runner/tool-completed
                                                   {:tool-id tool-id}))
      :gate/started (colorize :yellow (messages/t :workflow-runner/gate-started
                                                  {:gate gate}))
      :gate/passed (colorize :green (messages/t :workflow-runner/gate-passed
                                                {:gate gate}))
      :gate/failed (colorize :red (messages/t :workflow-runner/gate-failed
                                              {:gate gate}))
      :dependency/health-updated
      (dependency-event-line event
                             :workflow-runner/dependency-health-updated
                             (case (:dependency/status event)
                               (:operator-action-required :misconfigured :unavailable) :red
                               :degraded :yellow
                               :green))
      :dependency/recovered
      (dependency-event-line event
                             :workflow-runner/dependency-recovered
                             :green)
      :chain/completed (str (colorize :green (messages/t :workflow-runner/chain-step-completed
                                                                 {:chain-id (name (get event :chain/id :unknown))}))
                            (when-let [d (:chain/duration-ms event)]
                              (str " (" (format-duration d) ")")))
      nil)))

(defn- strip-ansi
  "Remove ANSI escape codes from a string."
  [s]
  (str/replace s #"\u001b\[[0-9;]*m" ""))

(defn- demo-defaults
  "Fill in '?' defaults for nil event params so format-event-line produces
   visible placeholders instead of empty strings."
  [event]
  (let [evt (:event/type event)]
    (cond-> event
      (and (#{:workflow/phase-started :workflow/phase-completed} evt)
           (nil? (or (:workflow/phase event) (:phase event))))
      (assoc :workflow/phase "?")

      (and (#{:agent/started :agent/completed :agent/failed :agent/status} evt)
           (nil? (or (:agent/id event) (:agent event))))
      (assoc :agent/id "?")

      (and (#{:gate/started :gate/passed :gate/failed} evt)
           (nil? (or (:gate/id event) (:gate event))))
      (assoc :gate/id "?")

      (and (#{:tool/invoked :tool/completed} evt)
           (nil? (:tool/id event)))
      (assoc :tool/id "?")

      (and (= :workflow/milestone-reached evt)
           (nil? (:message event)))
      (assoc :message "?")

      (and (= :workflow/failed evt)
           (nil? (:workflow/failure-reason event)))
      (assoc :workflow/failure-reason "unknown"))))

(defn format-demo-line
  "Format a plain-text (no ANSI) progress line for demo/test output.
   Delegates to format-event-line with nil-defaulted params, then strips ANSI.
   Uses '?' for nil values and 'unknown' for missing failure reasons."
  [event]
  (let [evt (:event/type event)
        patched (demo-defaults event)
        base (format-event-line patched)]
    (when base
      (let [stripped (strip-ansi base)]
        (case evt
          ;; Append (?) when duration is missing
          :workflow/completed (if (nil? (:workflow/duration-ms event))
                                (str stripped " (?)")
                                stripped)
          :workflow/phase-completed (if (nil? (:phase/duration-ms event))
                                      (str stripped " (?)")
                                      stripped)
          stripped)))))

(defn start-progress!
  "Subscribe to lifecycle events and print concise progress lines.
   Returns a cleanup function."
  [event-stream quiet?]
  (if (or quiet? (nil? event-stream))
    (fn [] nil)
    (let [sub-id (keyword (str "progress-" (random-uuid)))
          last-line (atom nil)]
      (es/subscribe! event-stream sub-id
                     (fn [event]
                       (when-let [line (format-event-line event)]
                         ;; Deduplicate back-to-back duplicates from layered emitters.
                         (when-not (= line @last-line)
                           (reset! last-line line)
                           (println line)
                           (flush)))))
      (fn []
        (es/unsubscribe! event-stream sub-id)))))

(defn print-workflow-summary [result]
  (let [{:execution/keys [status metrics errors]} result
        success? (= status :completed)]
    (println (if success?
               (colorize :green (messages/t :workflow-runner/summary-success))
               (colorize :red (messages/t :workflow-runner/summary-failure))))
    (when metrics
      (println (messages/t :workflow-runner/metrics
                           {:tokens (:tokens metrics 0)
                            :cost (format "%.4f" (:cost-usd metrics 0.0))
                            :duration (format-duration (:duration-ms metrics 0))})))
    (when (seq errors)
      (println (colorize :red (str "\n" (messages/t :workflow-runner/errors))))
      (doseq [err errors]
        (println (str "  • " err))))))

;------------------------------------------------------------------------------ Layer 1b
;; Compact summary helpers (pure)

(defn- phase-outcome-symbol
  "Return a display symbol for a phase outcome keyword."
  [outcome]
  (case outcome
    :failure "✗"
    :failed  "✗"
    :error   "✗"
    :skipped "○"
    "✓"))

(defn- phase-summary-outcome
  [phase-result]
  (let [status (get phase-result :status :unknown)]
    (case status
      :success :completed
      :completed :completed
      :already-implemented :completed
      :done :completed
      :error :failure
      :failed :failure
      :failure :failure
      status)))

(defn- phase-summary-duration-ms
  [phase-result]
  (or (:duration-ms phase-result)
      (get-in phase-result [:metrics :duration-ms])
      (get-in phase-result [:phase/metrics :duration-ms])))

(defn- phase-result-summary-entry
  [[phase-id phase-result]]
  (when (map? phase-result)
    {:phase       phase-id
     :outcome     (phase-summary-outcome phase-result)
     :duration-ms (phase-summary-duration-ms phase-result)}))

(defn- workflow-phase-result-entries
  [result]
  (when (map? result)
    (seq (:execution/phase-results result))))

(defn extract-phase-summaries
  "Extract phase summaries from canonical workflow execution results.

  Reads only `:execution/phase-results`, the workflow context's documented
  phase-result authority. Returns a vec of
  {:phase :outcome :duration-ms} maps, or nil when no phase breakdown is found."
  [result]
  (when-let [entries (workflow-phase-result-entries result)]
    (not-empty (vec (keep phase-result-summary-entry entries)))))

(defn extract-failed-tasks
  "Read failed DAG task ids from the canonical workflow DAG result.

  Returns a vec of task-id strings, or nil when the workflow result does not
  carry `[:execution/dag-result :failed-task-ids]`."
  [result]
  (when-let [ids (seq (get-in result [:execution/dag-result :failed-task-ids]))]
    (not-empty (vec (map #(if (keyword? %) (name %) (str %)) ids)))))

(defn- pr-info-url
  [pr-info]
  (or (:pr-url pr-info)
      (:pr/url pr-info)))

(defn- workflow-pr-infos
  [result]
  (concat (get result :execution/dag-pr-infos [])
          (when-let [pr-info (:workflow/pr-info result)]
            [pr-info])))

(defn extract-pr-urls
  "Extract PR URLs from workflow-owned PR info.

  Reads the canonical workflow context producers for PR info:
  `:execution/dag-pr-infos` for DAG runs and `:workflow/pr-info` for the
  release phase's single-PR path. Returns a vec of URL strings, or nil."
  [result]
  (when (map? result)
    (let [all (distinct (keep pr-info-url (workflow-pr-infos result)))]
      (not-empty (vec all)))))

(defn- format-phase-line
  "Format a single phase summary line using message catalog."
  [{:keys [phase outcome duration-ms]}]
  (let [symbol (phase-outcome-symbol outcome)
        phase-str (cond
                    (nil? phase)     "?"
                    (keyword? phase) (name phase)
                    :else            (str phase))]
    (if duration-ms
      (messages/t :workflow-runner/compact-phase-line
                  {:symbol   symbol
                   :phase    phase-str
                   :duration (format-duration duration-ms)})
      (messages/t :workflow-runner/compact-phase-no-dur
                  {:symbol symbol
                   :phase  phase-str}))))

(defn- compact-status-line
  [success?]
  (if success?
    (colorize :green (messages/t :workflow-runner/summary-success))
    (colorize :red   (messages/t :workflow-runner/summary-failure))))

(defn- compact-phase-lines
  [result]
  (map format-phase-line (or (extract-phase-summaries result) [])))

(defn- compact-failed-task-lines
  [result]
  (when-let [task-ids (extract-failed-tasks result)]
    [(messages/t :workflow-runner/compact-failed-tasks
                 {:task-ids (str/join ", " task-ids)})]))

(defn- compact-pr-lines
  [result]
  (when-let [urls (extract-pr-urls result)]
    [(messages/t :workflow-runner/compact-prs-created
                 {:urls (str/join "  " urls)})]))

(defn- compact-metrics-lines
  [metrics]
  (when metrics
    [(messages/t :workflow-runner/metrics
                 {:tokens   (get metrics :tokens 0)
                  :cost     (format "%.4f" (get metrics :cost-usd 0.0))
                  :duration (format-duration (get metrics :duration-ms 0))})]))

(defn- compact-error-lines
  [success? errors]
  (when (and (not success?) (seq errors))
    (into [(colorize :red (str "\n" (messages/t :workflow-runner/errors)))]
          (map #(str "  • " %) errors))))

(defn- compact-footer-lines
  [hr]
  [(colorize :cyan (str hr "\n"))
   (messages/t :workflow-runner/compact-events-pointer
               {:events-dir (app-config/events-dir)})
   (messages/t :workflow-runner/compact-full-hint)])

(defn- compact-summary-lines
  [result]
  (let [{:execution/keys [status metrics errors]} result
        success? (= status :completed)
        hr       (apply str (repeat 65 "━"))]
    (concat [(colorize :cyan (str "\n" hr))
             (compact-status-line success?)]
            (compact-phase-lines result)
            (compact-failed-task-lines result)
            (compact-pr-lines result)
            (compact-metrics-lines metrics)
            (compact-error-lines success? errors)
            (compact-footer-lines hr))))

(defn format-compact-summary
  "Build a compact multi-line string summarising a workflow result.

  Lines included:
  - Status (success/failure, colorized)
  - Phase table (when phase data present)
  - Failed task IDs (when present)
  - PR URLs (when present)
  - Metrics line (when metrics present)
  - Error list (on failure only)
  - Events directory pointer
  - '--output edn' hint

  All user-facing strings go through messages/t."
  ([result] (format-compact-summary result nil))
  ([result _workflow-id]
   (str/join "\n" (compact-summary-lines result))))

(defn print-pretty-result
  "Print a compact human-readable summary of the workflow result.
   Full EDN dump is available via --output edn."
  [result]
  (println (format-compact-summary result)))

(defn print-result [result {:keys [output quiet]}]
  (when-not quiet
    (case output
      :json   (println (json/generate-string result {:pretty true}))
      :pretty (print-pretty-result result)
      :edn    (clojure.pprint/pprint result)
      (clojure.pprint/pprint result)))
  (flush))

;------------------------------------------------------------------------------ Layer 2
;; Error help output

(defn print-error-header
  "Print error header with message, details, and cause."
  [msg data cause]
  (println (colorize :red (str "\n" (messages/t :workflow-runner/load-failed))))
  (println (messages/t :workflow-runner/error {:message msg}))
  (when data
    (println (messages/t :workflow-runner/details {:details (pr-str data)})))
  (when cause
    (println (messages/t :workflow-runner/cause {:cause (ex-message cause)})))
  (println (colorize :yellow (str "\n" (messages/t :workflow-runner/possible-causes))))
  (println (messages/t :workflow-runner/cause-missing-dep))
  (println (messages/t :workflow-runner/cause-compile))
  (println (messages/t :workflow-runner/cause-cycle)))

(defn print-namespace-resolution-help
  "Print help for namespace resolution errors."
  []
  (println (colorize :cyan (str "\n" (messages/t :workflow-runner/namespace-help-header))))
  (println (messages/t :workflow-runner/namespace-help-dep))
  (println (messages/t :workflow-runner/namespace-help-build)))

(defn print-babashka-fallback-help
  "Print help for Babashka compatibility issues."
  []
  (println (colorize :cyan (str "\n" (messages/t :workflow-runner/bb-help-header))))
  (println (messages/t :workflow-runner/bb-help-command)))

(defn print-general-debugging-help
  "Print general debugging tips."
  []
  (println (colorize :cyan (str "\n" (messages/t :workflow-runner/debug-header))))
  (println (messages/t :workflow-runner/debug-command)))
