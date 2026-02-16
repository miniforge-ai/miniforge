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

(ns ai.miniforge.tui-views.update.selection
  "Selection manipulation -- multi-select and visual mode.

   Pure functions for managing selected-ids set. Provides toggle,
   visual range selection, select-all, and clear operations.
   Layer 2."
  (:require
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update.navigation :as nav]))

;------------------------------------------------------------------------------ Layer 0
;; Filtered index resolution

(defn- raw-index
  "Map a cursor index to a raw list index, respecting filtered-indices.
   When a search filter is active, cursor index n refers to the nth visible
   item, not the nth item in the raw vector."
  [model idx]
  (if-let [fi (:filtered-indices model)]
    (let [sorted-fi (vec (sort fi))]
      (get sorted-fi idx))
    idx))

;; ID resolution

(defn item-id-at
  "Get the selectable ID for the item at the given index in the current view.
   Respects filtered-indices when active.
   Returns nil if index is out of bounds."
  [model idx]
  (case (:view model)
    :workflow-list
    (let [raw-idx (raw-index model idx)]
      (when raw-idx
        (:id (get (:workflows model) raw-idx))))

    :pr-fleet
    (let [raw-idx (raw-index model idx)]
      (when-let [pr (get (:pr-items model) raw-idx)]
        [(:pr/repo pr) (:pr/number pr)]))

    :artifact-browser
    (let [raw-idx (raw-index model idx)]
      (when (get (get-in model [:detail :artifacts] []) raw-idx)
        [:artifact raw-idx]))

    :train-view
    (let [prs (vec (sort-by :pr/merge-order
                     (get-in model [:detail :selected-train :train/prs] [])))
          raw-idx (raw-index model idx)]
      (when-let [pr (get prs raw-idx)]
        [(:pr/repo pr) (:pr/number pr)]))

    :repo-manager
    (let [raw-idx (raw-index model idx)]
      (get (model/repo-manager-items model) raw-idx))

    nil))

(defn current-item-id
  "Get ID of item at cursor position."
  [model]
  (item-id-at model (:selected-idx model)))

;------------------------------------------------------------------------------ Layer 1
;; Individual toggle

(defn toggle-selection
  "Toggle selection of item at cursor, then advance cursor down.
   Standard TUI multi-select pattern (like mc/ranger)."
  [model]
  (if-let [id (current-item-id model)]
    (-> model
        (update :selected-ids
                (fn [ids]
                  (let [ids (or ids #{})]
                    (if (contains? ids id)
                      (disj ids id)
                      (conj ids id)))))
        (nav/navigate-down))
    model))

;------------------------------------------------------------------------------ Layer 1
;; Visual mode

(defn enter-visual-mode
  "Set visual anchor at current cursor position."
  [model]
  (let [id (current-item-id model)]
    (-> model
        (assoc :visual-anchor (:selected-idx model))
        (assoc :selected-ids (if id #{id} #{})))))

(defn exit-visual-mode
  "Clear visual anchor without clearing selections."
  [model]
  (assoc model :visual-anchor nil))

(defn update-visual-selection
  "Recompute selected-ids from visual anchor to current cursor.
   No-op when visual-anchor is nil -- safe to call after every j/k."
  [model]
  (if-let [anchor (:visual-anchor model)]
    (let [lo (min anchor (:selected-idx model))
          hi (max anchor (:selected-idx model))
          ids (set (keep #(item-id-at model %) (range lo (inc hi))))]
      (assoc model :selected-ids ids))
    model))

;------------------------------------------------------------------------------ Layer 1
;; Bulk operations

(defn select-all
  "Select all items in current list."
  [model]
  (let [cnt (nav/list-count model)
        ids (set (keep #(item-id-at model %) (range cnt)))]
    (assoc model :selected-ids ids)))

(defn clear-selection
  "Clear all selections and exit visual mode."
  [model]
  (assoc model :selected-ids #{} :visual-anchor nil))

;------------------------------------------------------------------------------ Layer 2
;; Query helpers

(defn has-selection?
  "True if any items are selected."
  [model]
  (boolean (seq (:selected-ids model))))

(defn effective-ids
  "Return selected IDs for batch operation.
   If nothing selected, return the single cursor item ID wrapped in a set."
  [model]
  (if (has-selection? model)
    (:selected-ids model)
    (if-let [id (current-item-id model)]
      #{id}
      #{})))

(defn selection-count
  "Number of currently selected items."
  [model]
  (count (:selected-ids model)))
