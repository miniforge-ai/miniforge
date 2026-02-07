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
;; Rendering

(defn render
  "Render the workflow list view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [workflows (:workflows model)
        selected (:selected-idx model)
]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Title bar
      (fn [[c r]]
        (layout/text [c r] " MINIFORGE │ Workflows"
                     {:fg :cyan :bold? true}))
      ;; Main content + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          ;; Table
          (fn [[tc tr]]
            (if (empty? workflows)
              (layout/text [tc tr] "  No active workflows. Waiting for events..."
                           {:fg :default})
              (layout/table [tc tr]
                {:columns [{:key :status-char :header "  " :width 2}
                           {:key :name :header "Workflow" :width (max 10 (- tc 50))}
                           {:key :phase :header "Phase" :width 12}
                           {:key :progress-str :header "Progress" :width 20}
                           {:key :agent-msg :header "Agent" :width 16}]
                 :data (mapv (fn [wf]
                               {:status-char (case (:status wf)
                                               :running "●"
                                               :success "✓"
                                               :failed  "✗"
                                               :blocked "◐"
                                               "○")
                                :name (:name wf)
                                :phase (some-> (:phase wf) name)
                                :progress-str (str (:progress wf 0) "%")
                                :agent-msg (when-let [agent (first (vals (:agents wf)))]
                                             (when-let [msg (:message agent)]
                                               (subs msg 0 (min 16 (count msg)))))})
                             workflows)
                 :selected-row selected})))
          ;; Footer
          (fn [[fc fr]]
            (layout/text [fc fr]
              (str " j/k:navigate  Enter:detail  1-5:views  /:search  ::cmd  q:quit"
                   (when-let [flash (:flash-message model)]
                     (str "  │ " flash)))
              {:fg :default})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m {:workflows [{:name "deploy-v2" :status :running :phase :implement :progress 40}]
          :selected-idx 0})
  (layout/buf->strings (render m [80 24]))
  :leave-this-here)
