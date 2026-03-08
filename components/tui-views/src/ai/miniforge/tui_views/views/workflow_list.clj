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

(ns ai.miniforge.tui-views.views.workflow-list
  "Workflow list view -- N5 Section 3.2.1.

   Shows all active and recent workflows in a table with:
   - Status indicator (colored dot)
   - Workflow name
   - Current phase
   - Progress bar
   - Agent status
   - Temporal grouping (Today, This Week, Older)"
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout])
  (:import
   [java.time LocalDate ZoneId]
   [java.time.temporal ChronoUnit]))

;------------------------------------------------------------------------------ Layer 0
;; Temporal grouping

(defn temporal-bucket
  "Classify a workflow's started-at into a temporal group."
  [wf]
  (if-let [started (:started-at wf)]
    (let [wf-date (-> (if (instance? java.util.Date started)
                        (.toInstant ^java.util.Date started)
                        (java.time.Instant/parse (str started)))
                      (.atZone (ZoneId/systemDefault))
                      .toLocalDate)
          today (LocalDate/now)
          days-ago (.between ChronoUnit/DAYS wf-date today)]
      (cond
        (zero? days-ago)                   :today
        (= 1 days-ago)                     :yesterday
        (<= days-ago 7)                    :this-week
        (<= days-ago 30)                   :this-month
        :else                              :older))
    :unknown))

(def bucket-labels
  {:today      "Today"
   :yesterday  "Yesterday"
   :this-week  "This Week"
   :this-month "This Month"
   :older      "Older"
   :unknown    "Unknown"})

(def bucket-order [:today :yesterday :this-week :this-month :older :unknown])

(defn group-workflows
  "Group workflows into temporal buckets, preserving order within each group.
   Returns flat vector of {:type :header/:row, ...} entries for table rendering."
  [workflows]
  (let [grouped (group-by temporal-bucket workflows)
        buckets (filter #(contains? grouped %) bucket-order)]
    (into []
          (mapcat (fn [bucket]
                    (let [wfs (get grouped bucket)
                          label (str "── " (get bucket-labels bucket) " (" (count wfs) ") ")]
                      (cons {:type :header :label label :bucket bucket}
                            (map (fn [wf] {:type :row :wf wf}) wfs)))))
          buckets)))

;------------------------------------------------------------------------------ Layer 0b
;; Rendering helpers

(defn status-char [status]
  (case status
    :running  "●"
    :success  "✓"
    :failed   "✗"
    :blocked  "◐"
    :archived "⊘"
    "○"))

(defn chain-prefix
  "Build a chain progress prefix like '[plan-impl 2/4] ' when this workflow
   is the active chain step. Returns empty string otherwise."
  [wf active-chain]
  (when-let [{:keys [chain-id current-step step-count]} active-chain]
    (when (and current-step
               (= (:id wf) (:instance-id current-step)))
      (str "[" (name chain-id) " "
           (inc (:step-index current-step)) "/" step-count "] "))))

(defn format-workflow-row
  "Transform workflow data into table row format.
   active-chain is the :active-chain map from the model (or nil)."
  [wf active-chain]
  (let [prefix (or (chain-prefix wf active-chain) "")]
    {:status-char (status-char (:status wf))
     :name (str prefix (:name wf))
     :phase (some-> (:phase wf) name)
     :progress-str (str (:progress wf 0) "%")
     :agent-msg (when-let [agent (first (vals (:agents wf)))]
                  (when-let [msg (:message agent)]
                    (subs msg 0 (min 16 (count msg)))))}))

(defn render-title-bar [[cols rows]]
  (layout/text [cols rows] " MINIFORGE │ Workflows"
               {:fg [0 150 180] :bold? true}))

(defn auto-scroll-offset
  "Compute scroll offset so selected-idx is always visible within visible-count rows."
  [selected-idx visible-count]
  (let [sel (or selected-idx 0)]
    (if (<= (inc sel) visible-count)
      0
      (inc (- sel visible-count)))))

(defn format-grouped-row
  "Format a grouped entry (header or workflow row) for table rendering."
  [entry active-chain _cols]
  (if (= :header (:type entry))
    ;; Section header — spans full width with dimmed color
    {:status-char ""
     :name (:label entry)
     :name-fg [0 150 180]
     :phase ""
     :progress-str ""
     :agent-msg ""
     :header? true}
    (format-workflow-row (:wf entry) active-chain)))

(defn grouped-selected-row
  "Map a flat selected-idx (over non-header workflows) to the row index
   within the grouped list (which includes header rows)."
  [grouped-entries selected-idx]
  (loop [entries grouped-entries
         wf-idx 0
         row-idx 0]
    (if (empty? entries)
      row-idx
      (let [entry (first entries)]
        (cond
          (= :header (:type entry))
          (recur (rest entries) wf-idx (inc row-idx))

          (= wf-idx selected-idx)
          row-idx

          :else
          (recur (rest entries) (inc wf-idx) (inc row-idx)))))))


(defn render-table [workflows selected active-chain [cols rows]]
  (if (empty? workflows)
    (layout/text [cols rows] "  No active workflows. Waiting for events..."
                 {:fg :default})
    (let [grouped (group-workflows workflows)
          visible-count (max 0 (- rows 2))
          mapped-selected (grouped-selected-row grouped selected)
          offset (auto-scroll-offset mapped-selected visible-count)]
      (layout/table [cols rows]
        {:columns [{:key :status-char :header "  " :width 2}
                   {:key :name :header "Workflow" :width (max 10 (- cols 50))}
                   {:key :phase :header "Phase" :width 12}
                   {:key :progress-str :header "Progress" :width 20}
                   {:key :agent-msg :header "Agent" :width 16}]
         :data (mapv #(format-grouped-row % active-chain cols) grouped)
         :selected-row mapped-selected
         :offset offset}))))

(defn render-footer [flash-message [cols rows]]
  (layout/text [cols rows]
    (str " j/k:nav  Enter:detail  /:search(status:failed)  ::cmd  q:quit"
         (when flash-message (str "  │ " flash-message)))
    {:fg :default}))

(defn render
  "Render the workflow list view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [all-wfs (vec (remove #(= :archived (:status %)) (:workflows model)))
        workflows (if-let [fi (:filtered-indices model)]
                    (vec (keep-indexed (fn [i wf] (when (contains? fi i) wf)) all-wfs))
                    all-wfs)
        selected (:selected-idx model)
        active-chain (:active-chain model)
        flash (:flash-message model)
        search-active? (and (:filtered-indices model) (= :search (:mode model)))]
    (let [render-header-bar
          (fn [[tc tr]]
            (layout/text [tc tr]
              (str " MINIFORGE │ Workflows"
                   (when search-active? (str " [" (count workflows) " matches]")))
              {:fg [0 150 180] :bold? true}))

          render-content-area
          (fn [[c r]]
            (layout/split-v [c r] (/ (- r 2.0) r)
              (fn [size] (render-table workflows selected active-chain size))
              (fn [size] (render-footer flash size))))]
      (layout/split-v [cols rows] (/ 2.0 rows)
        render-header-bar
        render-content-area))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m {:workflows [{:name "deploy-v2" :status :running :phase :implement :progress 40}]
          :selected-idx 0})
  (layout/buf->strings (render m [80 24]))
  :leave-this-here)
