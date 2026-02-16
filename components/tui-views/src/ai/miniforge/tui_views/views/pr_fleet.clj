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
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.views.tab-bar :as tab-bar]))

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

(defn- pr-id
  "Composite ID for a PR item."
  [pr]
  [(:pr/repo pr) (:pr/number pr)])

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

(defn- render-pr-table [prs selected selected-ids theme [cols rows]]
  (if (empty? prs)
    (layout/text [cols rows]
                 "  No PRs. Manage repositories in Repos (6) then press r to refresh."
                 {:fg (get theme :fg :default)})
    (let [show-sel? (seq selected-ids)
          sel-w (if show-sel? 3 0)
          data (mapv (fn [pr]
                       (cond-> (format-pr-row pr)
                         show-sel?
                         (assoc :sel (if (contains? selected-ids (pr-id pr)) "[x]" "[ ]"))))
                     prs)
          columns (cond-> []
                    show-sel? (conj {:key :sel :header "   " :width 3})
                    true (into [{:key :repo   :header "Repo"      :width 18}
                                {:key :number :header "PR"        :width 6}
                                {:key :title  :header "Title"
                                 :width (max 10 (- cols 68 sel-w))}
                                {:key :status :header "Status"    :width 12}
                                {:key :ready  :header "Readiness" :width 15}
                                {:key :risk   :header "Risk"      :width 6}
                                {:key :policy :header "Policy"    :width 6}]))]
      (layout/table [cols rows]
        {:columns columns :data data :selected-row selected
         :header-fg (get theme :header :cyan)
         :row-fg (get theme :row-fg :default)
         :row-bg (get theme :row-bg :default)
         :selected-fg (get theme :selected-fg :white)
         :selected-bg (get theme :selected-bg :blue)}))))

(defn- render-footer [model [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        sel-count (count (:selected-ids model))
        visual? (:visual-anchor model)
        flash (:flash-message model)]
    (layout/text [cols rows]
      (cond
        (pos? sel-count)
        (str " " sel-count " selected │ Space:toggle  v:visual  a:all  c:clear  :archive :delete"
             (when flash (str "  │ " flash)))

        visual?
        (str " VISUAL │ j/k:extend  Space:toggle  Esc:exit  a:all"
             (when flash (str "  │ " flash)))

        :else
        (str " j/k:nav  Enter:detail  Tab:views  /:search  r:refresh (s:alias)  6:repos  q:quit"
             (when flash (str "  │ " flash))))
      {:fg (get theme :fg-dim :default)})))

(defn render
  "Render the PR fleet view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        prs (:pr-items model [])
        selected (:selected-idx model)
        selected-ids (or (:selected-ids model) #{})
        repo-count (count (distinct (map :pr/repo prs)))
        merge-ready (count (filter #(= :merge-ready (:pr/status %)) prs))]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Tab bar
      (fn [[c r]]
        (tab-bar/render model
                        (str "Repos: " repo-count
                             " | PRs: " (count prs)
                             " | Ready: " merge-ready)
                        [c r]))
      ;; Content + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [size] (render-pr-table prs selected selected-ids theme size))
          (fn [size] (render-footer model size)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
