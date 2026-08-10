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
(ns ai.miniforge.cli.workflow-runner.display-summary-lines
  "The individual lines that make up a compact workflow summary."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.display-ansi :as ansi]
   [ai.miniforge.cli.workflow-runner.display-format :as fmt]
   [ai.miniforge.cli.workflow-runner.display-result-facts :as facts]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} phase-outcome-symbol
  "Return a display symbol for a phase outcome keyword."
  [outcome]
  (case outcome
    :failure "✗"
    :failed  "✗"
    :error   "✗"
    :skipped "○"
    "✓"))

(defn ^{:stratum 0} failed-task-lines
  [result]
  (when-let [task-ids (facts/extract-failed-tasks result)]
    [(messages/t :workflow-runner/compact-failed-tasks
                 {:task-ids (str/join ", " task-ids)})]))

(defn ^{:stratum 0} metrics-lines
  [metrics]
  (when metrics
    [(messages/t :workflow-runner/metrics
                 {:tokens   (get metrics :tokens 0)
                  :cost     (format "%.4f" (get metrics :cost-usd 0.0))
                  :duration (fmt/format-duration (get metrics :duration-ms 0))})]))

(defn ^{:stratum 0} status-line
  [success?]
  (if success?
    (ansi/colorize :green (messages/t :workflow-runner/summary-success))
    (ansi/colorize :red   (messages/t :workflow-runner/summary-failure))))

(defn ^{:stratum 0} pr-lines
  [result]
  (when-let [urls (facts/extract-pr-urls result)]
    [(messages/t :workflow-runner/compact-prs-created
                 {:urls (str/join "  " urls)})]))

(defn ^{:stratum 0} error-lines
  [success? errors]
  (when (and (not success?) (seq errors))
    (into [(ansi/colorize :red (str "\n" (messages/t :workflow-runner/errors)))]
          (map #(str "  • " %) errors))))

(defn ^{:stratum 0} footer-lines
  [hr]
  [(ansi/colorize :cyan (str hr "\n"))
   (messages/t :workflow-runner/compact-events-pointer
               {:events-dir (app-config/events-dir)})
   (messages/t :workflow-runner/compact-full-hint)])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} format-phase-line
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
                   :duration (fmt/format-duration duration-ms)})
      (messages/t :workflow-runner/compact-phase-no-dur
                  {:symbol symbol
                   :phase  phase-str}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} phase-lines
  [result]
  (map format-phase-line (or (facts/extract-phase-summaries result) [])))
