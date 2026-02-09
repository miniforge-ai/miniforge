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

(ns ai.miniforge.tui-views.update.events
  "Event stream message handlers.

   Pure functions that handle workflow events from the event stream.
   Layer 2."
  (:require
   [ai.miniforge.tui-views.model :as model]))

;------------------------------------------------------------------------------ Layer 2
;; Event stream message handlers

;; Helper functions

(defn- find-workflow-idx
  "Find index of workflow with given ID in workflows vector."
  [workflows workflow-id]
  (some (fn [[i wf]] (when (= (:id wf) workflow-id) i))
        (map-indexed vector workflows)))

(defn- update-workflow-at
  "Update workflow at index using update-fn."
  [model idx update-fn]
  (if idx
    (update-in model [:workflows idx] update-fn)
    model))

(defn- update-detail-if-active
  "Apply update-fn to detail if workflow-id matches active detail."
  [model workflow-id update-fn]
  (if (= workflow-id (get-in model [:detail :workflow-id]))
    (update-fn model)
    model))

;; Event handlers

(defn handle-workflow-added [model {:keys [workflow-id name spec]}]
  (let [wf (model/make-workflow {:id workflow-id
                                  :name (or name (:name spec))
                                  :status :running})]
    (update model :workflows conj wf)))

(defn handle-phase-changed [model {:keys [workflow-id phase]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (cond-> model
      idx (assoc-in [:workflows idx :phase] phase)
      true (update-detail-if-active workflow-id
             #(update-in % [:detail :phases] conj {:phase phase :status :running})))))

(defn handle-phase-done [model {:keys [workflow-id]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (update-workflow-at model idx
      #(update % :progress (fn [p] (min 100 (+ (or p 0) 20)))))))

(defn handle-agent-status [model {:keys [workflow-id agent status message]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (cond-> model
      idx (assoc-in [:workflows idx :agents agent] {:status status :message message})
      true (update-detail-if-active workflow-id
             #(assoc-in % [:detail :current-agent] {:agent agent :status status :message message})))))

(defn handle-agent-output [model {:keys [workflow-id delta]}]
  (update-detail-if-active model workflow-id
    #(update-in % [:detail :agent-output] str delta)))

(defn handle-workflow-done [model {:keys [workflow-id status]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (update-workflow-at model idx
      #(-> %
           (assoc :status (or status :success))
           (assoc :progress 100)))))

(defn handle-workflow-failed [model {:keys [workflow-id error]}]
  (let [idx (find-workflow-idx (:workflows model) workflow-id)]
    (update-workflow-at model idx
      #(-> %
           (assoc :status :failed)
           (assoc :error error)))))

(defn handle-gate-result [model _payload]
  model)
