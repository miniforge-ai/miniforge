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

(ns ai.miniforge.tui-views.views.workflow-detail
  "Workflow detail view -- N5 Section 3.2.2.

   Shows detailed information about a single workflow:
   - Phase progress with status indicators
   - Real-time agent output streaming
   - Current phase and agent status"
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-engine.interface.widget :as widget]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn find-workflow [model]
  (let [wf-id (get-in model [:detail :workflow-id])]
    (some #(when (= (:id %) wf-id) %) (:workflows model))))

(defn status-suffix [status]
  (case status
    :running  " ●"
    :success  " ✓"
    :failed   " ✗"
    ""))

(defn format-cost [cost-usd]
  (when (and cost-usd (pos? cost-usd))
    (format "$%.4f" (double cost-usd))))

(defn format-tokens [tokens]
  (when (and tokens (pos? tokens))
    (if (>= tokens 1000)
      (format "%.1fk" (/ (double tokens) 1000.0))
      (str tokens))))

(defn format-phase-node [{:keys [phase status tokens cost-usd]}]
  (let [suffix (status-suffix status)
        metrics (str/join " " (keep identity
                                     [(format-tokens tokens)
                                      (format-cost cost-usd)]))]
    {:label (str (name phase) suffix
                 (when (seq metrics) (str "  " metrics)))
     :depth 0
     :expandable? false}))

(defn render-title-bar [wf detail [cols rows]]
  (let [metrics-parts (keep identity
                             [(format-tokens (:tokens detail))
                              (format-cost (:cost-usd detail))])
        metrics-str (when (seq metrics-parts)
                      (str " │ " (str/join " " metrics-parts)))]
    (layout/text [cols rows]
                 (str " MINIFORGE │ "
                      (get wf :name "Workflow Detail")
                      (when-let [phase (:phase wf)]
                        (str " │ " (name phase)))
                      metrics-str)
                 {:fg :cyan :bold? true})))

(defn render-phase-list [phases [cols rows]]
  (layout/box [cols rows]
    {:title "Phases" :border :single :fg :default
     :content-fn
     (fn [[ic ir]]
       (if (empty? phases)
         (layout/text [ic ir] "  Waiting for phases...")
         (widget/tree [ic ir]
           {:nodes (mapv format-phase-node phases)
            :selected 0})))}))

(defn render-agent-output [agent output [cols rows]]
  (layout/box [cols rows]
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
            :fg :white})))}))

(defn render-footer [[cols rows]]
  (layout/text [cols rows]
    " Esc:back  3:evidence  4:artifacts  5:dag  j/k:scroll  q:quit"
    {:fg :default}))

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
      (fn [size] (render-title-bar wf detail size))
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [[mc mr]]
            (layout/split-h [mc mr] 0.35
              (fn [size] (render-phase-list phases size))
              (fn [size] (render-agent-output agent output size))))
          render-footer)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def m {:workflows [{:id (random-uuid) :name "deploy-v2" :phase :implement}]
          :detail {:workflow-id (random-uuid)
                   :phases [{:phase :plan :status :success}
                            {:phase :implement :status :running}]
                   :current-agent {:agent :implementer :status :running}
                   :agent-output "Generating code..."}})
  (layout/buf->strings (render m [80 24]))
  :leave-this-here)
