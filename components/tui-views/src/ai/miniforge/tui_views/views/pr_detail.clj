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
  "PR detail view -- shows detailed information about a single PR.

   Displays PR title, readiness score, risk level, CI status,
   and review information."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Rendering helpers

(defn render-title-bar [pr [cols rows]]
  (layout/text [cols rows]
    (str " MINIFORGE │ "
         (or (:pr/title pr) "PR Detail"))
    {:fg :cyan :bold? true}))

(defn render-info-panel [pr [cols rows]]
  (layout/box [cols rows]
    {:title "PR Info" :border :single :fg :default
     :content-fn
     (fn [[ic ir]]
       (let [lines [(str "  Title:     " (or (:pr/title pr) "—"))
                    (str "  Repo:      " (or (:pr/repo pr) "—"))
                    (str "  Readiness: " (int (* 100 (or (:pr/readiness pr) 0))) "%")
                    (str "  Risk:      " (name (or (:pr/risk pr) :unknown)))
                    (str "  CI:        " (name (or (:pr/ci-status pr) :unknown)))
                    (str "  Author:    " (or (:pr/author pr) "—"))]]
         (layout/text [ic ir] (str/join "\n" lines))))}))

(defn render-footer [[cols rows]]
  (layout/text [cols rows]
    " Esc:back  Tab:pane  e:evidence  /:search  q:quit"
    {:fg :default}))

(defn render
  "Render the PR detail view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [pr (get-in model [:detail :selected-pr])]
    (layout/split-v [cols rows] (/ 2.0 rows)
      (fn [size] (render-title-bar pr size))
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [size] (render-info-panel pr size))
          render-footer)))))
