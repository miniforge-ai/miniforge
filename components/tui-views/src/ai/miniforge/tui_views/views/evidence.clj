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

(ns ai.miniforge.tui-views.views.evidence
  "Evidence viewer -- N5 Section 3.2.3.

   Displays workflow evidence as a scrollable tree with
   expand/collapse sections:
   - Intent (original task description)
   - Phases (plan, implement, verify with outcomes)
   - Validation (gate results and errors)
   - Policy (policy check results)"
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-engine.interface.widget :as widget]
   [ai.miniforge.tui-views.views.tab-bar :as tab-bar]))

;------------------------------------------------------------------------------ Layer 0
;; Evidence tree construction

(defn- build-evidence-tree
  "Build a tree node list from model evidence data."
  [model]
  (let [detail (:detail model)
        evidence (:evidence detail)
        phases (:phases detail)]
    (into []
      (concat
       ;; Intent section
       [{:label "Intent" :depth 0 :expandable? true}
        {:label (or (get-in evidence [:intent :description])
                    "No intent data available")
         :depth 1 :expandable? false}]
       ;; Phases section
       [{:label "Phases" :depth 0 :expandable? true}]
       (mapv (fn [{:keys [phase status]}]
               {:label (str (name phase)
                            (case status
                              :running  " ● running"
                              :success  " ✓ passed"
                              :failed   " ✗ failed"
                              ""))
                :depth 1 :expandable? false})
             phases)
       ;; Validation section
       [{:label "Validation" :depth 0 :expandable? true}
        {:label (if (get-in evidence [:validation :passed?])
                  "✓ All gates passed"
                  (str "✗ " (count (get-in evidence [:validation :errors] [])) " error(s)"))
         :depth 1 :expandable? false}]
       ;; Policy section
       [{:label "Policy" :depth 0 :expandable? true}
        {:label (if (get-in evidence [:policy :compliant?])
                  "✓ Policy compliant"
                  "✗ Policy violations detected")
         :depth 1 :expandable? false}]))))

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn render
  "Render the evidence viewer.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        nodes (build-evidence-tree model)
        selected (:selected-idx model)]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Tab bar
      (fn [[c r]]
        (let [wf-id (get-in model [:detail :workflow-id])
              wf-name (some #(when (= (:id %) wf-id) (:name %))
                            (:workflows model))]
          (tab-bar/render model (or wf-name "Evidence") [c r])))
      ;; Content + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          ;; Tree view
          (fn [[tc tr]]
            (layout/box [tc tr]
              {:title "Evidence" :border :single :fg (get theme :border :default)
               :content-fn
               (fn [[ic ir]]
                 (widget/tree [ic ir]
                   {:nodes nodes
                    :expanded (or (get-in model [:detail :expanded-nodes])
                                  #{0 2 (+ 3 (count (get-in model [:detail :phases])))
                                    (+ 5 (count (get-in model [:detail :phases])))})
                    :selected selected}))}))
          ;; Footer
          (fn [[fc fr]]
            (let [matches (:search-matches model)
                  match-idx (:search-match-idx model)
                  match-info (when (seq matches)
                               (str "MATCH [" (inc (or match-idx 0)) "/"
                                    (count matches) "] │ "))]
              (layout/text [fc fr]
                (str " " (or match-info "")
                     "j/k:navigate  Space:expand  /:search  n/N:next/prev  Esc:back  q:quit")
                {:fg (get theme :fg-dim :default)}))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
