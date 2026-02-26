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
   Layers 0-1."
  (:require
   [ai.miniforge.tui-views.effect :as effect]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.transition :as transition]))

;------------------------------------------------------------------------------ Layer 0
;; Navigation helpers

(defn list-count
  "Count of items visible in the current list. Respects filtered-indices."
  [model]
  (let [raw-count (case (:view model)
                    :workflow-list    (count (:workflows model))
                    :artifact-browser (count (get-in model [:detail :artifacts]))
                    :pr-fleet         (count (:pr-items model))
                    :repo-manager     (count (model/repo-manager-items model))
                    :train-view       (count (get-in model [:detail :selected-train :train/prs]))
                    0)]
    (if-let [fi (:filtered-indices model)]
      (count fi)
      raw-count)))

(defn- raw-workflow-index
  "Map a cursor index to a raw workflow vector index via filtered-indices.
   Returns cursor index directly when no filter is active."
  [model idx]
  (if-let [fi (:filtered-indices model)]
    (nth (vec (sort fi)) idx nil)
    idx))

(defn- scrollable-view?
  "Views where j/k scroll content rather than moving a list cursor."
  [view]
  (= :workflow-detail view))

(defn navigate-up [model]
  (if (scrollable-view? (:view model))
    (update model :scroll-offset #(max 0 (dec (or % 0))))
    (update model :selected-idx #(max 0 (dec %)))))

(defn navigate-down [model]
  (if (scrollable-view? (:view model))
    ;; scroll-offset upper bound is clamped at render time
    (update model :scroll-offset #(inc (or % 0)))
    (let [max-idx (max 0 (dec (list-count model)))]
      (update model :selected-idx #(min max-idx (inc %))))))

(defn navigate-top [model]
  (assoc model :selected-idx 0 :scroll-offset 0))

(defn navigate-bottom [model]
  (let [max-idx (max 0 (dec (list-count model)))]
    (assoc model :selected-idx max-idx)))

;------------------------------------------------------------------------------ Layer 1
;; View navigation

(defn- visible-prs
  "Return the visible PR list, respecting any active filter."
  [model]
  (let [prs (:pr-items model)]
    (if-let [fi (:filtered-indices model)]
      (into [] (keep-indexed #(when (contains? fi %1) %2)) prs)
      prs)))

(defn- enter-workflow-detail [model]
  (let [raw-idx (raw-workflow-index model (:selected-idx model))]
    (if-let [wf (when raw-idx (get (:workflows model) raw-idx))]
      (-> model
          (assoc :view :workflow-detail)
          (assoc-in [:detail :workflow-id] (:id wf))
          (assoc :selected-idx 0 :selected-ids #{} :visual-anchor nil
                 :scroll-offset nil :search-matches [] :search-match-idx nil))
      model)))

(defn- enter-pr-detail [model]
  (if-let [pr (get (visible-prs model) (:selected-idx model))]
    (let [pr-id [(:pr/repo pr) (:pr/number pr)]]
      (cond-> (-> model
                  (assoc :view :pr-detail)
                  (assoc-in [:detail :selected-pr] pr)
                  (assoc :selected-idx 0 :selected-ids #{} :visual-anchor nil))
        (nil? (:pr/policy pr))
        (assoc :side-effect (effect/evaluate-policy pr-id pr))))
    model))

(defn- enter-train-detail [model]
  (let [prs (get-in model [:detail :selected-train :train/prs])]
    (if-let [pr (get (vec prs) (:selected-idx model))]
      (-> model
          (assoc :view :pr-detail)
          (assoc-in [:detail :selected-pr] pr)
          (assoc :selected-idx 0 :selected-ids #{} :visual-anchor nil))
      model)))

(defn enter-detail [model]
  (case (:view model)
    :workflow-list (enter-workflow-detail model)
    :pr-fleet      (enter-pr-detail model)
    :train-view    (enter-train-detail model)
    model))

(defn go-back [model]
  (let [clear {:selected-idx 0 :selected-ids #{} :visual-anchor nil
               :search-matches [] :search-match-idx nil :scroll-offset 0}]
    (case (:view model)
      ;; Detail views go back to their parent aggregate
      ;; Clear :detail :workflow-id so Tab stays at the aggregate tier
      :workflow-detail  (-> (merge model clear {:view :workflow-list})
                            (assoc-in [:detail :workflow-id] nil))
      :pr-detail        (-> (merge model clear {:view :pr-fleet})
                            (assoc-in [:detail :selected-pr] nil))
      :train-view       (-> (merge model clear {:view :pr-fleet})
                            (assoc-in [:detail :selected-train] nil))
      ;; Evidence / artifact-browser as detail sub-views → back to workflow-list
      ;; As top-level aggregates (no workflow-id) → no-op
      (:evidence :artifact-browser)
      (if (some? (get-in model [:detail :workflow-id]))
        (-> (merge model clear {:view :workflow-list})
            (assoc-in [:detail :workflow-id] nil))
        model)
      ;; Top-level aggregate views: no-op (use Tab to cycle)
      model)))

(defn switch-view [model view-key views]
  (if (some #{view-key} views)
    (assoc model :view view-key :selected-idx 0 :scroll-offset 0
           :selected-ids #{} :visual-anchor nil)
    model))

;------------------------------------------------------------------------------ Layer 2
;; Action helpers

(defn refresh [model]
  (assoc model
         :flash-message "Refreshed"
         :last-updated (java.util.Date.)))

(defn toggle-help [model]
  (update model :help-visible? not))

(defn toggle-expand [model]
  (let [idx (:selected-idx model)]
    (update-in model [:detail :expanded-nodes]
               (fn [nodes]
                 (let [nodes (or nodes #{})]
                   (if (contains? nodes idx)
                     (disj nodes idx)
                     (conj nodes idx)))))))

(defn cycle-pane
  "Cycle Tab focus between panes in multi-pane views.
   Views with multiple columns/panes define their pane count;
   single-pane views are a no-op."
  [model]
  (let [pane-count (case (:view model)
                     :workflow-detail 2   ;; phases | agent output
                     :pr-detail       3   ;; readiness | risk | gates
                     :dag-kanban      6   ;; 6 kanban columns
                     1)
        current (get-in model [:detail :focused-pane] 0)]
    (assoc-in model [:detail :focused-pane]
              (mod (inc current) pane-count))))

;------------------------------------------------------------------------------ Layer 3
;; Detail screen navigation — sibling items + sub-view cycling

(def ^:private workflow-subviews
  "Sub-views for a workflow item, cycled with Tab."
  [:workflow-detail :evidence :artifact-browser])

(def ^:private workflow-subview-set
  (set workflow-subviews))

(defn in-detail-subview?
  "True when the model is in a workflow detail sub-view context.
   This means the current view is one of the workflow sub-views AND
   a workflow-id is present in :detail (i.e. we drilled in from a list,
   not navigated directly to a top-level aggregate)."
  [model]
  (and (contains? workflow-subview-set (:view model))
       (some? (get-in model [:detail :workflow-id]))))

(defn current-level-views
  "Return views for the current abstraction level (max 10 keys per level).
   - Top-level aggregate: model/top-level-views
   - Workflow detail context: workflow sub-views
   - Other detail views: model/detail-views"
  [model]
  (cond
    (in-detail-subview? model)
    workflow-subviews

    (some #{(:view model)} model/detail-views)
    model/detail-views

    :else
    model/top-level-views))

(defn- find-workflow-idx
  "Find the index of a workflow-id in the workflows vector."
  [workflows wf-id]
  (some (fn [[i wf]] (when (= (:id wf) wf-id) i))
        (map-indexed vector workflows)))

(defn navigate-prev-item
  "Navigate to the previous item's detail view.
   In workflow detail/evidence/artifact-browser: show previous workflow.
   In pr-detail: show previous PR.
   No-op in list views or at boundary."
  [model]
  (case (:view model)
    (:workflow-detail :evidence :artifact-browser)
    (let [workflows (:workflows model)
          wf-id (get-in model [:detail :workflow-id])
          current-idx (find-workflow-idx workflows wf-id)
          prev-idx (when current-idx (dec current-idx))]
      (if (and prev-idx (>= prev-idx 0))
        (let [wf (get workflows prev-idx)]
          (-> model
              (assoc-in [:detail :workflow-id] (:id wf))
              (assoc :selected-idx 0)
              (assoc-in [:detail :expanded-nodes] #{})))
        model))

    :pr-detail
    (let [prs (:pr-items model)
          current-pr (get-in model [:detail :selected-pr])
          current-idx (some (fn [[i pr]] (when (= pr current-pr) i))
                            (map-indexed vector prs))
          prev-idx (when current-idx (dec current-idx))]
      (if (and prev-idx (>= prev-idx 0))
        (let [pr (get prs prev-idx)]
          (-> model
              (assoc-in [:detail :selected-pr] pr)
              (assoc :selected-idx 0)))
        model))

    ;; Non-detail views: no-op
    model))

(defn navigate-next-item
  "Navigate to the next item's detail view.
   In workflow detail/evidence/artifact-browser: show next workflow.
   In pr-detail: show next PR.
   No-op in list views or at boundary."
  [model]
  (case (:view model)
    (:workflow-detail :evidence :artifact-browser)
    (let [workflows (:workflows model)
          wf-id (get-in model [:detail :workflow-id])
          current-idx (find-workflow-idx workflows wf-id)
          next-idx (when current-idx (inc current-idx))]
      (if (and next-idx (< next-idx (count workflows)))
        (let [wf (get workflows next-idx)]
          (-> model
              (assoc-in [:detail :workflow-id] (:id wf))
              (assoc :selected-idx 0)
              (assoc-in [:detail :expanded-nodes] #{})))
        model))

    :pr-detail
    (let [prs (:pr-items model)
          current-pr (get-in model [:detail :selected-pr])
          current-idx (some (fn [[i pr]] (when (= pr current-pr) i))
                            (map-indexed vector prs))
          next-idx (when current-idx (inc current-idx))]
      (if (and next-idx (< next-idx (count prs)))
        (let [pr (get prs next-idx)]
          (-> model
              (assoc-in [:detail :selected-pr] pr)
              (assoc :selected-idx 0)))
        model))

    ;; Non-detail views: no-op
    model))

;------------------------------------------------------------------------------ Layer 4
;; Search match navigation

(defn next-search-match
  "Jump to the next search match. Wraps around.
   In scrollable views (workflow-detail): updates scroll-offset.
   In tree/table views (evidence): updates selected-idx.
   No-op when no matches active."
  [model]
  (let [matches (:search-matches model)
        idx (:search-match-idx model)]
    (if (or (empty? matches) (nil? idx))
      model
      (let [next-idx (mod (inc idx) (count matches))
            match (get matches next-idx)
            line-idx (:line-idx match 0)]
        (cond-> (assoc model :search-match-idx next-idx)
          (scrollable-view? (:view model))
          (assoc :scroll-offset line-idx)
          (not (scrollable-view? (:view model)))
          (assoc :selected-idx line-idx))))))

(defn prev-search-match
  "Jump to the previous search match. Wraps around."
  [model]
  (let [matches (:search-matches model)
        idx (:search-match-idx model)]
    (if (or (empty? matches) (nil? idx))
      model
      (let [prev-idx (mod (+ idx (dec (count matches))) (count matches))
            match (get matches prev-idx)
            line-idx (:line-idx match 0)]
        (cond-> (assoc model :search-match-idx prev-idx)
          (scrollable-view? (:view model))
          (assoc :scroll-offset line-idx)
          (not (scrollable-view? (:view model)))
          (assoc :selected-idx line-idx))))))

(defn cycle-detail-subview
  "Cycle Tab through sub-views for the same item.
   workflow-detail → evidence → artifact-browser → workflow-detail."
  [model]
  (let [current (:view model)
        idx (.indexOf workflow-subviews current)
        next-view (if (>= idx 0)
                    (get workflow-subviews (mod (inc idx) (count workflow-subviews)))
                    current)]
    (-> model
        (assoc :view next-view)
        (assoc :selected-idx 0)
        (assoc-in [:detail :expanded-nodes] #{}))))

(defn cycle-detail-subview-reverse
  "Cycle Shift+Tab through sub-views in reverse.
   workflow-detail ← evidence ← artifact-browser ← workflow-detail."
  [model]
  (let [current (:view model)
        n (count workflow-subviews)
        idx (.indexOf workflow-subviews current)
        prev-view (if (>= idx 0)
                    (get workflow-subviews (mod (+ idx (dec n)) n))
                    current)]
    (-> model
        (assoc :view prev-view)
        (assoc :selected-idx 0)
        (assoc-in [:detail :expanded-nodes] #{}))))

(defn cycle-top-level-view
  "Cycle Tab through top-level aggregate views.
   pr-fleet → workflow-list → evidence → artifact-browser → dag-kanban → repo-manager → pr-fleet.
   Clears detail context so Tab stays at the aggregate tier."
  [model]
  (let [current (:view model)
        views model/top-level-views
        idx (.indexOf views current)
        next-view (if (>= idx 0)
                    (get views (mod (inc idx) (count views)))
                    (first views))]
    (transition/switch-view model next-view)))

(defn cycle-top-level-view-reverse
  "Cycle Shift+Tab through top-level aggregate views in reverse.
   pr-fleet ← repo-manager ← dag-kanban ← artifact-browser ← evidence ← workflow-list ← pr-fleet.
   Clears detail context so Tab stays at the aggregate tier."
  [model]
  (let [current (:view model)
        views model/top-level-views
        n (count views)
        idx (.indexOf views current)
        prev-view (if (>= idx 0)
                    (get views (mod (+ idx (dec n)) n))
                    (last views))]
    (transition/switch-view model prev-view)))

(defn cycle-pane-reverse
  "Cycle Shift+Tab focus between panes in reverse."
  [model]
  (let [pane-count (case (:view model)
                     :workflow-detail 2
                     :pr-detail       3
                     :dag-kanban      6
                     1)
        current (get-in model [:detail :focused-pane] 0)]
    (assoc-in model [:detail :focused-pane]
              (mod (+ current (dec pane-count)) pane-count))))
