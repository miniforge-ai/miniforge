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

(ns ai.miniforge.tui-views.update
  "Pure update function: (model, msg) -> model'.

   All state transitions for the TUI application. Imports handlers from
   stratified sub-namespaces (navigation, events, mode) and provides input
   handling and root update dispatch.

   Layers 4-5: Input handling + root update function."
  (:require
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update.navigation :as nav]
   [ai.miniforge.tui-views.update.events :as events]
   [ai.miniforge.tui-views.update.mode :as mode]))

;------------------------------------------------------------------------------ Layer 4
;; Input message handling

(defn- handle-normal-input [model key]
  (case key
    :key/j         (nav/navigate-down model)
    :key/k         (nav/navigate-up model)
    :key/down      (nav/navigate-down model)
    :key/up        (nav/navigate-up model)
    :key/g         (nav/navigate-top model)
    :key/G         (nav/navigate-bottom model)
    :key/enter     (nav/enter-detail model)
    :key/escape    (nav/go-back model)
    :key/l         (nav/enter-detail model)
    :key/h         (nav/go-back model)
    :key/colon     (mode/enter-command-mode model)
    :key/slash     (mode/enter-search-mode model)
    :key/d1        (nav/switch-view model :workflow-list model/views)
    :key/d2        (nav/switch-view model :workflow-detail model/views)
    :key/d3        (nav/switch-view model :evidence model/views)
    :key/d4        (nav/switch-view model :artifact-browser model/views)
    :key/d5        (nav/switch-view model :dag-kanban model/views)
    :key/q         (assoc model :quit? true)
    ;; Unknown key -- no-op
    model))

(defn- handle-command-input [model key]
  (case key
    :key/escape   (mode/exit-mode model)
    :key/enter    (-> model
                      (assoc :flash-message (str "Executed: " (:command-buf model)))
                      mode/exit-mode)
    :key/backspace (mode/command-backspace model)
    ;; Character input
    (if (and (map? key) (= :char (:type key)))
      (mode/command-append model (:char key))
      model)))

(defn- handle-search-input [model key]
  (case key
    :key/escape   (mode/exit-mode model)
    :key/enter    (mode/exit-mode model)
    :key/backspace (mode/command-backspace model)
    (if (and (map? key) (= :char (:type key)))
      (mode/command-append model (:char key))
      model)))

;------------------------------------------------------------------------------ Layer 5
;; Root update function

(defn update-model
  "Root update function for the TUI application.
   Pure: (model, msg) -> model'

   Messages are vectors: [msg-type payload]
   Input messages: [:input key-event]
   Stream messages: [:msg/workflow-added data], [:msg/phase-changed data], etc."
  [model msg]
  (let [[msg-type payload] (if (vector? msg) msg [msg nil])]
    (case msg-type
      ;; User input
      :input
      (case (:mode model)
        :normal  (handle-normal-input model payload)
        :command (handle-command-input model payload)
        :search  (handle-search-input model payload)
        model)

      ;; Event stream messages
      :msg/workflow-added   (events/handle-workflow-added model payload)
      :msg/phase-changed    (events/handle-phase-changed model payload)
      :msg/phase-done       (events/handle-phase-done model payload)
      :msg/agent-status     (events/handle-agent-status model payload)
      :msg/agent-output     (events/handle-agent-output model payload)
      :msg/workflow-done    (events/handle-workflow-done model payload)
      :msg/workflow-failed  (events/handle-workflow-failed model payload)
      :msg/gate-result      (events/handle-gate-result model payload)

      ;; Tick (for clock/timing updates, currently unused)
      :tick model

      ;; Unknown message -- no-op
      model)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m (model/init-model))

  ;; Navigate
  (-> m
      (update-model [:msg/workflow-added {:workflow-id (random-uuid) :name "test"}])
      (update-model [:msg/workflow-added {:workflow-id (random-uuid) :name "test-2"}])
      (update-model [:input :key/j])
      :selected-idx)
  ;; => 1

  :leave-this-here)
