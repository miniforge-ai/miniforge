;; Copyright 2025 miniforge.ai
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

   All state transitions for the TUI application. Each message type
   has a handler that returns a new model. No side effects."
  (:require
   [ai.miniforge.tui-views.model :as model]))

;------------------------------------------------------------------------------ Layer 0
;; Navigation helpers

(defn- list-count [model]
  (case (:view model)
    :workflow-list (count (:workflows model))
    :artifact-browser (count (get-in model [:detail :artifacts]))
    0))

(defn- navigate-up [model]
  (update model :selected-idx #(max 0 (dec %))))

(defn- navigate-down [model]
  (let [max-idx (max 0 (dec (list-count model)))]
    (update model :selected-idx #(min max-idx (inc %)))))

(defn- navigate-top [model]
  (assoc model :selected-idx 0 :scroll-offset 0))

(defn- navigate-bottom [model]
  (let [max-idx (max 0 (dec (list-count model)))]
    (assoc model :selected-idx max-idx)))

;------------------------------------------------------------------------------ Layer 1
;; View navigation

(defn- enter-detail [model]
  (let [workflows (:workflows model)
        idx (:selected-idx model)]
    (if-let [wf (get workflows idx)]
      (-> model
          (assoc :view :workflow-detail)
          (assoc-in [:detail :workflow-id] (:id wf))
          (assoc :selected-idx 0))
      model)))

(defn- go-back [model]
  (case (:view model)
    :workflow-detail (assoc model :view :workflow-list :selected-idx 0)
    :evidence        (assoc model :view :workflow-detail :selected-idx 0)
    :artifact-browser (assoc model :view :workflow-detail :selected-idx 0)
    :dag-kanban       (assoc model :view :workflow-list :selected-idx 0)
    model))

(defn- switch-view [model view-key]
  (if (some #{view-key} model/views)
    (assoc model :view view-key :selected-idx 0 :scroll-offset 0)
    model))

;------------------------------------------------------------------------------ Layer 2
;; Event stream message handlers

(defn- handle-workflow-added [model {:keys [workflow-id name spec]}]
  (let [wf (model/make-workflow {:id workflow-id
                                  :name (or name (:name spec))
                                  :status :running
                                  :started-at (java.time.Instant/now)})]
    (-> model
        (update :workflows conj wf)
        (assoc :last-updated (java.time.Instant/now)))))

(defn- handle-phase-changed [model {:keys [workflow-id phase]}]
  (let [idx (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
                  (map-indexed vector (:workflows model)))]
    (cond-> model
      idx (assoc-in [:workflows idx :phase] phase)
      (= workflow-id (get-in model [:detail :workflow-id]))
      (update-in [:detail :phases] conj {:phase phase :status :running})
      true (assoc :last-updated (java.time.Instant/now)))))

(defn- handle-phase-done [model {:keys [workflow-id]}]
  (let [idx (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
                  (map-indexed vector (:workflows model)))]
    (cond-> model
      idx (update-in [:workflows idx :progress]
                     #(min 100 (+ (or % 0) 20)))
      true (assoc :last-updated (java.time.Instant/now)))))

(defn- handle-agent-status [model {:keys [workflow-id agent status message]}]
  (let [idx (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
                  (map-indexed vector (:workflows model)))]
    (cond-> model
      idx (assoc-in [:workflows idx :agents agent] {:status status :message message})
      (= workflow-id (get-in model [:detail :workflow-id]))
      (assoc-in [:detail :current-agent] {:agent agent :status status :message message})
      true (assoc :last-updated (java.time.Instant/now)))))

(defn- handle-agent-output [model {:keys [workflow-id delta]}]
  (if (= workflow-id (get-in model [:detail :workflow-id]))
    (update-in model [:detail :agent-output] str delta)
    model))

(defn- handle-workflow-done [model {:keys [workflow-id status]}]
  (let [idx (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
                  (map-indexed vector (:workflows model)))]
    (cond-> model
      idx (-> (assoc-in [:workflows idx :status] (or status :success))
              (assoc-in [:workflows idx :progress] 100))
      true (assoc :last-updated (java.time.Instant/now)))))

(defn- handle-workflow-failed [model {:keys [workflow-id error]}]
  (let [idx (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
                  (map-indexed vector (:workflows model)))]
    (cond-> model
      idx (-> (assoc-in [:workflows idx :status] :failed)
              (assoc-in [:workflows idx :error] error))
      true (assoc :last-updated (java.time.Instant/now)))))

(defn- handle-gate-result [model _payload]
  (assoc model :last-updated (java.time.Instant/now)))

;------------------------------------------------------------------------------ Layer 3
;; Mode switching

(defn- enter-command-mode [model]
  (assoc model :mode :command :command-buf ":"))

(defn- enter-search-mode [model]
  (assoc model :mode :search :command-buf "/" :search-results []))

(defn- exit-mode [model]
  (assoc model :mode :normal :command-buf "" :search-results []))

(defn- command-append [model ch]
  (update model :command-buf str ch))

(defn- command-backspace [model]
  (let [buf (:command-buf model)]
    (if (> (count buf) 1)
      (assoc model :command-buf (subs buf 0 (dec (count buf))))
      (exit-mode model))))

;------------------------------------------------------------------------------ Layer 4
;; Input message handling

(defn- handle-normal-input [model key]
  (case key
    :key/j         (navigate-down model)
    :key/k         (navigate-up model)
    :key/down      (navigate-down model)
    :key/up        (navigate-up model)
    :key/g         (navigate-top model)
    :key/G         (navigate-bottom model)
    :key/enter     (enter-detail model)
    :key/escape    (go-back model)
    :key/l         (enter-detail model)
    :key/h         (go-back model)
    :key/colon     (enter-command-mode model)
    :key/slash     (enter-search-mode model)
    :key/d1        (switch-view model :workflow-list)
    :key/d2        (switch-view model :workflow-detail)
    :key/d3        (switch-view model :evidence)
    :key/d4        (switch-view model :artifact-browser)
    :key/d5        (switch-view model :dag-kanban)
    :key/q         (assoc model :quit? true)
    ;; Unknown key -- no-op
    model))

(defn- handle-command-input [model key]
  (case key
    :key/escape   (exit-mode model)
    :key/enter    (-> model
                      (assoc :flash-message (str "Executed: " (:command-buf model)))
                      exit-mode)
    :key/backspace (command-backspace model)
    ;; Character input
    (if (and (map? key) (= :char (:type key)))
      (command-append model (:char key))
      model)))

(defn- handle-search-input [model key]
  (case key
    :key/escape   (exit-mode model)
    :key/enter    (exit-mode model)
    :key/backspace (command-backspace model)
    (if (and (map? key) (= :char (:type key)))
      (command-append model (:char key))
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
      :msg/workflow-added   (handle-workflow-added model payload)
      :msg/phase-changed    (handle-phase-changed model payload)
      :msg/phase-done       (handle-phase-done model payload)
      :msg/agent-status     (handle-agent-status model payload)
      :msg/agent-output     (handle-agent-output model payload)
      :msg/workflow-done    (handle-workflow-done model payload)
      :msg/workflow-failed  (handle-workflow-failed model payload)
      :msg/gate-result      (handle-gate-result model payload)

      ;; Tick (for clock/timing updates)
      :tick (assoc model :last-updated (java.time.Instant/now))

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
