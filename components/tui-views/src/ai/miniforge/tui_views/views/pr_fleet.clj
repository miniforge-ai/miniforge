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

(ns ai.miniforge.tui-views.views.pr-fleet
  "PR Fleet view -- N5 Section 3.2.8 / N9.

   Shows PR Work Items across repositories with readiness, risk,
   and policy columns. Derived from event stream and PR Work Item state."
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- readiness-bar
  "Produce a compact text readiness bar for a table cell."
  [score width]
  (let [pct (int (* 100 (or score 0)))
        bar-w (max 1 (- width 5))
        filled (int (/ (* pct bar-w) 100))]
    (str (apply str (repeat filled \u2588))
         (apply str (repeat (- bar-w filled) \u2591))
         (format " %3d%%" pct))))

(defn- risk-label [level]
  (case level
    :critical "CRIT"
    :high     "high"
    :medium   "med"
    :low      "low"
    "?"))

(defn- format-pr-row [pr]
  {:repo    (or (:pr/repo pr) "")
   :number  (str "#" (:pr/number pr))
   :title   (or (:pr/title pr) "")
   :status  (some-> (:pr/status pr) name)
   :ready   (readiness-bar (:pr/readiness-score pr) 15)
   :risk    (risk-label (get-in pr [:pr/risk :risk/level]))
   :policy  (if (:pr/policy-passed? pr) "pass" "?")})

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn render
  "Render the PR fleet view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [prs (:pr-items model [])
        selected (:selected-idx model)
        repo-count (count (distinct (map :pr/repo prs)))
        merge-ready (count (filter #(= :merge-ready (:pr/status %)) prs))]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Title bar
      (fn [[c r]]
        (layout/text [c r]
                     (str " MINIFORGE │ PR Fleet"
                          " [Repos: " repo-count
                          " | PRs: " (count prs)
                          " | Ready: " merge-ready "]")
                     {:fg :cyan :bold? true}))
      ;; Content + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          ;; Table
          (fn [[tc tr]]
            (if (empty? prs)
              (layout/text [tc tr]
                           "  No PR work items. Connect a PR train to see data."
                           {:fg :default})
              (layout/table [tc tr]
                {:columns [{:key :repo   :header "Repo"      :width 18}
                           {:key :number :header "PR"        :width 6}
                           {:key :title  :header "Title"     :width (max 10 (- tc 68))}
                           {:key :status :header "Status"    :width 12}
                           {:key :ready  :header "Readiness" :width 15}
                           {:key :risk   :header "Risk"      :width 6}
                           {:key :policy :header "Policy"    :width 6}]
                 :data (mapv format-pr-row prs)
                 :selected-row selected})))
          ;; Footer
          (fn [[fc fr]]
            (layout/text [fc fr]
              " j/k:navigate  Enter:detail  8:train  Esc:back  q:quit"
              {:fg :default})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
