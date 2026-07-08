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
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.messages :as msg]
   [ai.miniforge.tui-views.palette :as palette]))

;------------------------------------------------------------------------------ Layer 0
;; Rendering helpers

(defn- status-label
  "Render a status keyword for display: the status's own name, or a localized
   'unknown' label when the status is absent."
  [status]
  (if status (name status) (msg/t :detail/status-unknown)))

(defn render-title-bar [pr [cols rows]]
  (layout/text [cols rows]
    (str (msg/t :detail/title-prefix)
         (get pr :pr/title (msg/t :detail/title-fallback)))
    {:fg palette/status-info :bold? true}))

(defn render-info-panel [pr [cols rows]]
  (layout/box [cols rows]
    {:title (msg/t :detail/panel-title) :border :single :fg :default
     :content-fn
     (fn [[ic ir]]
       (let [none (msg/t :detail/value-none)
             lines [(msg/t :detail/line-title {:value (get pr :pr/title none)})
                    (msg/t :detail/line-repo {:value (get pr :pr/repo none)})
                    (msg/t :detail/line-readiness {:pct (int (* 100 (get pr :pr/readiness 0)))})
                    (msg/t :detail/line-risk {:value (status-label (:pr/risk pr))})
                    (msg/t :detail/line-ci {:value (status-label (:pr/ci-status pr))})
                    (msg/t :detail/line-author {:value (get pr :pr/author none)})]]
         (layout/text [ic ir] (str/join "\n" lines))))}))

(defn render-footer [[cols rows]]
  (layout/text [cols rows]
    (msg/t :detail/footer)
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
