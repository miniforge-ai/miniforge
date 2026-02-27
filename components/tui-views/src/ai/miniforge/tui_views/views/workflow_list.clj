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
   - Agent status"
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Rendering helpers

(defn- status-char [status]
  (case status
    :running  "●"
    :success  "✓"
    :failed   "✗"
    :blocked  "◐"
    :archived "⊘"
    "○"))

(defn- chain-prefix
  "Build a chain progress prefix like '[plan-impl 2/4] ' when this workflow
   is the active chain step. Returns empty string otherwise."
  [wf active-chain]
  (when-let [{:keys [chain-id current-step step-count]} active-chain]
    (when (and current-step
               (= (:id wf) (:instance-id current-step)))
      (str "[" (name chain-id) " "
           (inc (:step-index current-step)) "/" step-count "] "))))

(defn- format-workflow-row
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

(defn- render-title-bar [[cols rows]]
  (layout/text [cols rows] " MINIFORGE │ Workflows"
               {:fg :cyan :bold? true}))

(defn- render-table [workflows selected active-chain [cols rows]]
  (if (empty? workflows)
    (layout/text [cols rows] "  No active workflows. Waiting for events..."
                 {:fg :default})
    (layout/table [cols rows]
      {:columns [{:key :status-char :header "  " :width 2}
                 {:key :name :header "Workflow" :width (max 10 (- cols 50))}
                 {:key :phase :header "Phase" :width 12}
                 {:key :progress-str :header "Progress" :width 20}
                 {:key :agent-msg :header "Agent" :width 16}]
       :data (mapv #(format-workflow-row % active-chain) workflows)
       :selected-row selected})))

(defn- render-footer [flash-message [cols rows]]
  (layout/text [cols rows]
    (str " j/k:navigate  Enter:detail  1-5:views  /:search  ::cmd  q:quit"
         (when flash-message (str "  │ " flash-message)))
    {:fg :default}))

(defn render
  "Render the workflow list view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [workflows (vec (remove #(= :archived (:status %)) (:workflows model)))
        selected (:selected-idx model)
        active-chain (:active-chain model)
        flash (:flash-message model)]
    (layout/split-v [cols rows] (/ 2.0 rows)
      (fn [size] (render-title-bar size))
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [size] (render-table workflows selected active-chain size))
          (fn [size] (render-footer flash size)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m {:workflows [{:name "deploy-v2" :status :running :phase :implement :progress 40}]
          :selected-idx 0})
  (layout/buf->strings (render m [80 24]))
  :leave-this-here)
