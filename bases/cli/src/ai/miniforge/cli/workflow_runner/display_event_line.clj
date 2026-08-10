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
(ns ai.miniforge.cli.workflow-runner.display-event-line
  "Colorized one-line rendering of workflow lifecycle events."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.display-ansi :as ansi]
   [ai.miniforge.cli.workflow-runner.display-format :as fmt]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} dependency-kind-label
  [event]
  (messages/t (keyword "workflow-runner.dependency-kind"
                       (name (or (:dependency/kind event) :environment)))))

(defn- ^{:stratum 0} dependency-status-label
  [event]
  (messages/t (keyword "workflow-runner.dependency-status"
                       (name (or (:dependency/status event) :healthy)))))

(defn- ^{:stratum 0} dependency-display-name
  [event]
  (or (some-> (:dependency/vendor event) fmt/humanize-keyword str/capitalize)
      (let [dependency-id (:dependency/id event)]
        (cond
          (keyword? dependency-id) (str/capitalize (fmt/humanize-keyword dependency-id))
          (string? dependency-id) dependency-id
          dependency-id (str dependency-id)
          :else nil))
      "?"))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} dependency-event-line
  [event message-key color]
  (ansi/colorize color
                 (messages/t message-key
                             {:dependency (dependency-display-name event)
                              :kind (dependency-kind-label event)
                              :status (dependency-status-label event)})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} format-event-line
  "Format a concise progress line for a lifecycle event. Returns nil for unknown events."
  [event]
  (let [evt (:event/type event)
        phase (or (:workflow/phase event) (:phase event))
        gate (or (:gate/id event) (:gate event))
        agent (or (:agent/id event) (:agent event))
        tool-id (:tool/id event)]
    (case evt
      :workflow/started (ansi/colorize :cyan (messages/t :workflow-runner/start))
      :workflow/completed (str (ansi/colorize :green (messages/t :workflow-runner/completed))
                               (when-let [d (:workflow/duration-ms event)]
                                 (str " (" (fmt/format-duration d) ")")))
      :workflow/failed (str (ansi/colorize :red (messages/t :workflow-runner/failed))
                            (when-let [r (:workflow/failure-reason event)]
                              (str ": " r)))
      :workflow/phase-started (ansi/colorize :yellow (messages/t :workflow-runner/phase-started
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
                                  (str (ansi/colorize color (messages/t :workflow-runner/phase-completed
                                                                        {:symbol symbol
                                                                         :phase phase
                                                                         :outcome (name outcome)}))
                                       (when-let [d (:phase/duration-ms event)]
                                         (str " (" (fmt/format-duration d) ")"))))
      :workflow/milestone-reached (ansi/colorize :green (messages/t :workflow-runner/milestone
                                                                    {:message (:message event)}))
      :workspace/persisted (ansi/colorize :cyan (messages/t :workflow-runner/workspace-persisted
                                                            {:phase       (some-> (:workspace/phase event) name)
                                                             :bundle-path (or (:workspace/bundle-path event)
                                                                              (:workspace/commit-sha event)
                                                                              "(no archive path)")}))
      :agent/started (ansi/colorize :cyan (messages/t :workflow-runner/agent-started
                                                      {:agent agent}))
      :agent/completed (ansi/colorize :green (messages/t :workflow-runner/agent-completed
                                                         {:agent agent}))
      :agent/failed (ansi/colorize :red (messages/t :workflow-runner/agent-failed
                                                    {:agent agent}))
      :agent/status (ansi/colorize :cyan (messages/t :workflow-runner/agent-status
                                                     {:agent agent
                                                      :status (or (:message event)
                                                                  (:status/type event)
                                                                  (messages/t :workflow-runner/default-status))}))
      :tool/invoked (ansi/colorize :yellow (messages/t :workflow-runner/tool-invoked
                                                       {:tool-id tool-id}))
      :tool/completed (ansi/colorize :green (messages/t :workflow-runner/tool-completed
                                                        {:tool-id tool-id}))
      :gate/started (ansi/colorize :yellow (messages/t :workflow-runner/gate-started
                                                       {:gate gate}))
      :gate/passed (ansi/colorize :green (messages/t :workflow-runner/gate-passed
                                                     {:gate gate}))
      :gate/failed (ansi/colorize :red (messages/t :workflow-runner/gate-failed
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
      :chain/completed (str (ansi/colorize :green (messages/t :workflow-runner/chain-step-completed
                                                              {:chain-id (name (get event :chain/id :unknown))}))
                            (when-let [d (:chain/duration-ms event)]
                              (str " (" (fmt/format-duration d) ")")))
      nil)))
