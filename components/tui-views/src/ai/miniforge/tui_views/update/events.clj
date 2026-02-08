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

(ns ai.miniforge.tui-views.update.events
  "Event stream message handlers.

   Pure functions that handle workflow events from the event stream.
   Layer 2."
  (:require
   [ai.miniforge.tui-views.model :as model]))

;------------------------------------------------------------------------------ Layer 2
;; Event stream message handlers

(defn handle-workflow-added [model {:keys [workflow-id name spec]}]
  (let [wf (model/make-workflow {:id workflow-id
                                  :name (or name (:name spec))
                                  :status :running})]
    (-> model
        (update :workflows conj wf))))

(defn handle-phase-changed [model {:keys [workflow-id phase]}]
  (let [idx (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
                  (map-indexed vector (:workflows model)))]
    (cond-> model
      idx (assoc-in [:workflows idx :phase] phase)
      (= workflow-id (get-in model [:detail :workflow-id]))
      (update-in [:detail :phases] conj {:phase phase :status :running}))))

(defn handle-phase-done [model {:keys [workflow-id]}]
  (let [idx (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
                  (map-indexed vector (:workflows model)))]
    (cond-> model
      idx (update-in [:workflows idx :progress]
                     #(min 100 (+ (or % 0) 20))))))

(defn handle-agent-status [model {:keys [workflow-id agent status message]}]
  (let [idx (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
                  (map-indexed vector (:workflows model)))]
    (cond-> model
      idx (assoc-in [:workflows idx :agents agent] {:status status :message message})
      (= workflow-id (get-in model [:detail :workflow-id]))
      (assoc-in [:detail :current-agent] {:agent agent :status status :message message}))))

(defn handle-agent-output [model {:keys [workflow-id delta]}]
  (if (= workflow-id (get-in model [:detail :workflow-id]))
    (update-in model [:detail :agent-output] str delta)
    model))

(defn handle-workflow-done [model {:keys [workflow-id status]}]
  (let [idx (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
                  (map-indexed vector (:workflows model)))]
    (cond-> model
      idx (-> (assoc-in [:workflows idx :status] (or status :success))
              (assoc-in [:workflows idx :progress] 100)))))

(defn handle-workflow-failed [model {:keys [workflow-id error]}]
  (let [idx (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
                  (map-indexed vector (:workflows model)))]
    (cond-> model
      idx (-> (assoc-in [:workflows idx :status] :failed)
              (assoc-in [:workflows idx :error] error)))))

(defn handle-gate-result [model _payload]
  model)
