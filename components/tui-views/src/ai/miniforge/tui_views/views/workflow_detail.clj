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

(ns ai.miniforge.tui-views.views.workflow-detail
  "Workflow detail view -- N5 Section 3.2.2.

   Shows detailed information for a single workflow:
   - Phase pipeline with status indicators
   - Current agent activity and streaming output
   - Progress breakdown by phase"
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-engine.interface.widget :as widget]))

;------------------------------------------------------------------------------ Layer 0
;; Helper: find workflow

(defn- find-workflow [model]
  (let [wf-id (get-in model [:detail :workflow-id])]
    (some #(when (= (:id %) wf-id) %) (:workflows model))))

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn render
  "Render the workflow detail view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [wf (find-workflow model)
        detail (:detail model)
        phases (:phases detail)
        agent (:current-agent detail)
        output (:agent-output detail)]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Title bar
      (fn [[c r]]
        (layout/text [c r]
                     (str " MINIFORGE │ "
                          (or (:name wf) "Workflow Detail")
                          (when-let [phase (:phase wf)]
                            (str " │ " (name phase))))
                     {:fg :cyan :bold? true}))
      ;; Content + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          ;; Main content: left phases + right agent output
          (fn [[mc mr]]
            (layout/split-h [mc mr] 0.35
              ;; Left pane: phase list
              (fn [[lc lr]]
                (layout/box [lc lr]
                  {:title "Phases" :border :single :fg :default
                   :content-fn
                   (fn [[ic ir]]
                     (if (empty? phases)
                       (layout/text [ic ir] "  Waiting for phases...")
                       (let [nodes (mapv (fn [{:keys [phase status]}]
                                           {:label (str (name phase)
                                                        (case status
                                                          :running  " ●"
                                                          :success  " ✓"
                                                          :failed   " ✗"
                                                          ""))
                                            :depth 0
                                            :expandable? false})
                                         phases)]
                         (widget/tree [ic ir] {:nodes nodes :selected 0}))))}))
              ;; Right pane: agent output
              (fn [[rc rr]]
                (layout/box [rc rr]
                  {:title (if agent
                            (str (name (:agent agent)) " - " (name (:status agent :idle)))
                            "Agent Output")
                   :border :single :fg :default
                   :content-fn
                   (fn [[ic ir]]
                     (let [lines (if (seq output)
                                   (str/split-lines output)
                                   ["  Waiting for agent output..."])
                           offset (max 0 (- (count lines) ir))]
                       (widget/scrollable [ic ir]
                         {:lines lines
                          :offset offset
                          :fg :white})))}))))
          ;; Footer
          (fn [[fc fr]]
            (layout/text [fc fr]
              " Esc:back  3:evidence  4:artifacts  5:dag  j/k:scroll  q:quit"
              {:fg :default})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
