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
(ns ai.miniforge.tui-views.msg
  "Message constructors for the Elm update loop.

   Every TUI message is a vector [msg-type payload]. This namespace
   provides named constructors so the format is defined in one place
   and callers read as a DSL rather than raw data literals.

   Layer 0 — no dependencies on other tui-views namespaces.")

;------------------------------------------------------------------------------ Layer 0

;; Side-effect result messages (returned by effect handlers)
(defn ^{:stratum 0} prs-synced
  ([pr-items] [:msg/prs-synced {:pr-items pr-items}])
  ([pr-items error] [:msg/prs-synced (cond-> {:pr-items pr-items}
                                       error (assoc :error error))]))

(defn ^{:stratum 0} prs-synced-with-cache
  "Like prs-synced but includes pre-loaded cache data so the reducer
   doesn't need to perform filesystem IO."
  [pr-items cached-risk error]
  [:msg/prs-synced (cond-> {:pr-items pr-items
                            :cached-risk cached-risk}
                     error (assoc :error error))])

(defn ^{:stratum 0} repos-discovered [result]
  [:msg/repos-discovered result])

(defn ^{:stratum 0} repos-browsed [result]
  [:msg/repos-browsed result])

(defn ^{:stratum 0} policy-evaluated [pr-id result]
  [:msg/policy-evaluated {:pr-id pr-id :result result}])

(defn ^{:stratum 0} train-created [train-id train-name]
  [:msg/train-created {:train-id train-id :train-name train-name}])

(defn ^{:stratum 0} prs-added-to-train [train added]
  [:msg/prs-added-to-train {:train train :added added}])

(defn ^{:stratum 0} merge-started [pr-number train]
  [:msg/merge-started {:pr-number pr-number :train train}])

(defn ^{:stratum 0} review-completed [results]
  [:msg/review-completed {:results results}])

(defn ^{:stratum 0} remediation-completed [fixed failed message]
  [:msg/remediation-completed {:fixed fixed :failed failed :message message}])

(defn ^{:stratum 0} decomposition-started [pr-id plan]
  [:msg/decomposition-started (merge {:pr-id pr-id} plan)])

(defn ^{:stratum 0} pr-diff-fetched
  "Build a :msg/pr-diff-fetched message.
   Omits :error key when error is nil."
  [pr-id diff detail error]
  [:msg/pr-diff-fetched (cond-> {:pr-id pr-id :diff diff :detail detail}
                          error (assoc :error error))])

(defn ^{:stratum 0} chat-response [content actions]
  [:msg/chat-response {:content content :actions actions}])

(defn ^{:stratum 0} chat-action-result [result]
  [:msg/chat-action-result result])

(defn ^{:stratum 0} fleet-risk-triaged [assessments]
  [:msg/fleet-risk-triaged {:assessments assessments}])

(defn ^{:stratum 0} fleet-risk-triaged-error [error]
  [:msg/fleet-risk-triaged {:error error}])

(defn ^{:stratum 0} side-effect-error [error-data]
  [:msg/side-effect-error error-data])

(defn ^{:stratum 0} workflows-archived [result]
  [:msg/workflows-archived result])

(defn ^{:stratum 0} workflow-detail-loaded [workflow-id detail]
  [:msg/workflow-detail-loaded {:workflow-id workflow-id :detail detail}])

;; Event stream translation messages (from subscription.clj)
(defn ^{:stratum 0} workflow-added [wf-id wf-name spec]
  [:msg/workflow-added {:workflow-id wf-id :name wf-name :spec spec}])

(defn ^{:stratum 0} phase-changed [wf-id phase]
  [:msg/phase-changed {:workflow-id wf-id :phase phase}])

(defn ^{:stratum 0} phase-done [wf-id phase outcome & [extras]]
  [:msg/phase-done (merge {:workflow-id wf-id :phase phase :outcome outcome} extras)])

(defn ^{:stratum 0} agent-status [wf-id agent-id status-type message]
  [:msg/agent-status {:workflow-id wf-id :agent agent-id
                      :status status-type :message message}])

(defn ^{:stratum 0} agent-output [wf-id agent-id delta done?]
  [:msg/agent-output {:workflow-id wf-id :agent agent-id
                      :delta delta :done? done?}])

(defn ^{:stratum 0} agent-started [wf-id agent-id context]
  [:msg/agent-started {:workflow-id wf-id :agent agent-id :context context}])

(defn ^{:stratum 0} agent-completed [wf-id agent-id result]
  [:msg/agent-completed {:workflow-id wf-id :agent agent-id :result result}])

(defn ^{:stratum 0} agent-failed [wf-id agent-id error]
  [:msg/agent-failed {:workflow-id wf-id :agent agent-id :error error}])

(defn ^{:stratum 0} workflow-done [wf-id status & [extras]]
  [:msg/workflow-done (merge {:workflow-id wf-id :status status} extras)])

(defn ^{:stratum 0} workflow-failed [wf-id error]
  [:msg/workflow-failed {:workflow-id wf-id :error error}])

(defn ^{:stratum 0} gate-result [wf-id gate passed?]
  [:msg/gate-result {:workflow-id wf-id :gate gate :passed? passed?}])

(defn ^{:stratum 0} gate-started [wf-id gate]
  [:msg/gate-started {:workflow-id wf-id :gate gate}])

(defn ^{:stratum 0} tool-invoked [wf-id agent-id tool-id]
  [:msg/tool-invoked {:workflow-id wf-id :agent agent-id :tool tool-id}])

(defn ^{:stratum 0} tool-completed [wf-id agent-id tool-id]
  [:msg/tool-completed {:workflow-id wf-id :agent agent-id :tool tool-id}])

;; Chain event messages (from subscription.clj)
(defn ^{:stratum 0} chain-started [chain-id step-count]
  [:msg/chain-started {:chain-id chain-id :step-count step-count}])

(defn ^{:stratum 0} chain-step-started [chain-id step-id step-index workflow-id]
  [:msg/chain-step-started {:chain-id chain-id :step-id step-id
                            :step-index step-index :workflow-id workflow-id}])

(defn ^{:stratum 0} chain-step-completed [chain-id step-id step-index]
  [:msg/chain-step-completed {:chain-id chain-id :step-id step-id
                              :step-index step-index}])

(defn ^{:stratum 0} chain-step-failed [chain-id step-id step-index error]
  [:msg/chain-step-failed {:chain-id chain-id :step-id step-id
                           :step-index step-index :error error}])

(defn ^{:stratum 0} chain-completed [chain-id duration-ms step-count]
  [:msg/chain-completed {:chain-id chain-id :duration-ms duration-ms
                         :step-count step-count}])

(defn ^{:stratum 0} chain-failed [chain-id failed-step error]
  [:msg/chain-failed {:chain-id chain-id :failed-step failed-step
                      :error error}])

;; PR monitor event messages (from subscription.clj)
(defn ^{:stratum 0} pr-monitor-loop-started [pr-id config]
  [:msg/pr-monitor-loop-started {:pr-id pr-id :config config}])

(defn ^{:stratum 0} pr-monitor-loop-stopped [pr-id reason]
  [:msg/pr-monitor-loop-stopped {:pr-id pr-id :reason reason}])

(defn ^{:stratum 0} pr-monitor-fix-started [pr-id comment-id attempt]
  [:msg/pr-monitor-fix-started {:pr-id pr-id :comment-id comment-id :attempt attempt}])

(defn ^{:stratum 0} pr-monitor-fix-pushed [pr-id comment-id sha]
  [:msg/pr-monitor-fix-pushed {:pr-id pr-id :comment-id comment-id :sha sha}])

(defn ^{:stratum 0} pr-monitor-budget-warning [pr-id remaining total]
  [:msg/pr-monitor-budget-warning {:pr-id pr-id :remaining remaining :total total}])

(defn ^{:stratum 0} pr-monitor-budget-exhausted [pr-id data]
  [:msg/pr-monitor-budget-exhausted {:pr-id pr-id :data data}])

(defn ^{:stratum 0} pr-monitor-escalated [pr-id reason]
  [:msg/pr-monitor-escalated {:pr-id pr-id :reason reason}])

;; Control-plane event messages (from subscription.clj)
(defn ^{:stratum 0} control-plane-agent-discovered [session-id agent-data]
  [:msg/control-plane-agent-discovered {:session-id session-id :agent-data agent-data}])

(defn ^{:stratum 0} control-plane-status-changed [session-id status]
  [:msg/control-plane-status-changed {:session-id session-id :status status}])

(defn ^{:stratum 0} control-plane-decision-submitted [decision-id data]
  [:msg/control-plane-decision-submitted {:decision-id decision-id :data data}])

(defn ^{:stratum 0} control-plane-decision-resolved [decision-id outcome]
  [:msg/control-plane-decision-resolved {:decision-id decision-id :outcome outcome}])

;; Subscription health message
(defn ^{:stratum 0} subscription-status-changed [status last-event-at]
  [:msg/subscription-status-changed {:status status :last-event-at last-event-at}])
