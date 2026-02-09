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

(ns ai.miniforge.tui-views.update.navigation
  "Navigation helpers and view transitions.

   Pure functions for list navigation and view switching.
   Layers 0-1.")

;------------------------------------------------------------------------------ Layer 0
;; Navigation helpers

(defn list-count [model]
  (case (:view model)
    :workflow-list (count (:workflows model))
    :artifact-browser (count (get-in model [:detail :artifacts]))
    0))

(defn navigate-up [model]
  (update model :selected-idx #(max 0 (dec %))))

(defn navigate-down [model]
  (let [max-idx (max 0 (dec (list-count model)))]
    (update model :selected-idx #(min max-idx (inc %)))))

(defn navigate-top [model]
  (assoc model :selected-idx 0 :scroll-offset 0))

(defn navigate-bottom [model]
  (let [max-idx (max 0 (dec (list-count model)))]
    (assoc model :selected-idx max-idx)))

;------------------------------------------------------------------------------ Layer 1
;; View navigation

(defn enter-detail [model]
  (let [workflows (:workflows model)
        idx (:selected-idx model)]
    (if-let [wf (get workflows idx)]
      (-> model
          (assoc :view :workflow-detail)
          (assoc-in [:detail :workflow-id] (:id wf))
          (assoc :selected-idx 0))
      model)))

(defn go-back [model]
  (case (:view model)
    :workflow-detail (assoc model :view :workflow-list :selected-idx 0)
    :evidence        (assoc model :view :workflow-detail :selected-idx 0)
    :artifact-browser (assoc model :view :workflow-detail :selected-idx 0)
    :dag-kanban       (assoc model :view :workflow-list :selected-idx 0)
    model))

(defn switch-view [model view-key views]
  (if (some #{view-key} views)
    (assoc model :view view-key :selected-idx 0 :scroll-offset 0)
    model))
