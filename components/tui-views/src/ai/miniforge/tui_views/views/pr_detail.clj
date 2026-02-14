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

(ns ai.miniforge.tui-views.views.pr-detail
  "PR Detail view -- N5 Section 3.2.9 / N9.

   Shows readiness blockers, risk factors, and policy results
   for a single PR Work Item."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-engine.interface.widget :as widget]))

;------------------------------------------------------------------------------ Layer 0
;; Tree construction helpers

(defn- build-readiness-tree [readiness]
  (let [score  (or (:readiness/score readiness) 0)
        factors (:readiness/factors readiness [])]
    (into [{:label (str "Readiness: " (int (* 100 score)) "%")
            :depth 0 :expandable? true}]
          (mapv (fn [{:keys [factor weight score]}]
                  {:label (str (name factor) ": "
                               (int (* 100 (or score 0))) "%"
                               " (w=" (int (* 100 (or weight 0))) "%)")
                   :depth 1 :expandable? false})
                factors))))

(defn- build-risk-tree [risk]
  (let [level   (or (:risk/level risk) :unknown)
        factors (:risk/factors risk [])]
    (into [{:label (str "Risk: " (name level))
            :depth 0 :expandable? true}]
          (mapv (fn [{:keys [factor explanation]}]
                  {:label (str (name factor) ": " explanation)
                   :depth 1 :expandable? false})
                factors))))

(defn- build-gate-list [pr-data]
  (let [gates (get pr-data :pr/gate-results [])]
    (if (empty? gates)
      [{:label "No gate results" :depth 0 :expandable? false}]
      (mapv (fn [g]
              {:label (str (if (:gate/passed? g) "pass " "FAIL ")
                           (name (:gate/id g)))
               :depth 0 :expandable? false})
            gates))))

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn render
  "Render the PR detail view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [pr-data    (get-in model [:detail :selected-pr])
        readiness  (get-in model [:detail :pr-readiness])
        risk       (get-in model [:detail :pr-risk])
        expanded   (or (get-in model [:detail :expanded-nodes]) #{0})]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Title
      (fn [[c r]]
        (layout/text [c r]
          (str " MINIFORGE │ PR "
               (when (:pr/repo pr-data) (str (:pr/repo pr-data) " "))
               "#" (:pr/number pr-data "?")
               " " (:pr/title pr-data ""))
          {:fg :cyan :bold? true}))
      ;; Body + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          ;; Main content: left readiness, right risk+gates
          (fn [[mc mr]]
            (layout/split-h [mc mr] 0.4
              ;; Left: Readiness breakdown
              (fn [[lc lr]]
                (layout/box [lc lr]
                  {:title "Readiness" :border :single :fg :default
                   :content-fn
                   (fn [[ic ir]]
                     (widget/tree [ic ir]
                       {:nodes (build-readiness-tree readiness)
                        :expanded expanded
                        :selected (:selected-idx model)}))}))
              ;; Right: Risk + Gates stacked
              (fn [[rc rr]]
                (layout/split-v [rc rr] 0.5
                  ;; Risk assessment
                  (fn [[tc tr]]
                    (layout/box [tc tr]
                      {:title "Risk Assessment" :border :single :fg :default
                       :content-fn
                       (fn [[ic ir]]
                         (widget/tree [ic ir]
                           {:nodes (build-risk-tree risk)
                            :expanded expanded
                            :selected 0}))}))
                  ;; Gates/Policy
                  (fn [[gc gr]]
                    (layout/box [gc gr]
                      {:title "Gates & Policy" :border :single :fg :default
                       :content-fn
                       (fn [[ic ir]]
                         (widget/tree [ic ir]
                           {:nodes (build-gate-list pr-data)}))}))))))
          ;; Footer
          (fn [[fc fr]]
            (layout/text [fc fr]
              " Esc:back  6:fleet  8:train  Space:expand  q:quit"
              {:fg :default})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
