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

(defn prs-synced [pr-items]
  [:msg/prs-synced {:pr-items pr-items}])

(defn repos-discovered [result]
  [:msg/repos-discovered result])

(defn repos-browsed [result]
  [:msg/repos-browsed result])

(defn policy-evaluated [pr-id result]
  [:msg/policy-evaluated {:pr-id pr-id :result result}])

(defn train-created [train-id train-name]
  [:msg/train-created {:train-id train-id :train-name train-name}])

(defn prs-added-to-train [train added]
  [:msg/prs-added-to-train {:train train :added added}])

(defn merge-started [pr-number train]
  [:msg/merge-started {:pr-number pr-number :train train}])

(defn review-completed [results]
  [:msg/review-completed {:results results}])

(defn remediation-completed [fixed failed message]
  [:msg/remediation-completed {:fixed fixed :failed failed :message message}])

(defn decomposition-started [pr-id plan]
  [:msg/decomposition-started {:pr-id pr-id :plan plan}])

(defn chat-response [content actions]
  [:msg/chat-response {:content content :actions actions}])

(defn chat-action-result [result]
  [:msg/chat-action-result result])

(defn side-effect-error [error-data]
  [:msg/side-effect-error error-data])

;------------------------------------------------------------------------------ Layer 0b
;; Event stream translation messages (from subscription.clj)

(defn workflow-added [wf-id wf-name spec]
  [:msg/workflow-added {:workflow-id wf-id :name wf-name :spec spec}])

(defn phase-changed [wf-id phase]
  [:msg/phase-changed {:workflow-id wf-id :phase phase}])

(defn phase-done [wf-id phase outcome]
  [:msg/phase-done {:workflow-id wf-id :phase phase :outcome outcome}])

(defn agent-status [wf-id agent-id status-type message]
  [:msg/agent-status {:workflow-id wf-id :agent agent-id
                      :status status-type :message message}])

(defn agent-output [wf-id agent-id delta done?]
  [:msg/agent-output {:workflow-id wf-id :agent agent-id
                      :delta delta :done? done?}])

(defn workflow-done [wf-id status]
  [:msg/workflow-done {:workflow-id wf-id :status status}])

(defn workflow-failed [wf-id error]
  [:msg/workflow-failed {:workflow-id wf-id :error error}])

(defn gate-result [wf-id gate passed?]
  [:msg/gate-result {:workflow-id wf-id :gate gate :passed? passed?}])
