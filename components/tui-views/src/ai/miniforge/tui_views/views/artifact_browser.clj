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

(ns ai.miniforge.tui-views.views.artifact-browser
  "Artifact browser -- N5 Section 3.2.4.

   Shows workflow artifacts in a table with drill-down:
   - Type (plan, code, test, review, evidence)
   - Name / path
   - Size
   - Status"
  (:require
   [ai.miniforge.tui-engine.interface.layout :as layout]))

;------------------------------------------------------------------------------ Layer 0
;; Rendering

(defn render
  "Render the artifact browser.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [artifacts (get-in model [:detail :artifacts] [])
        selected (:selected-idx model)]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Title bar
      (fn [[c r]]
        (layout/text [c r] " MINIFORGE │ Artifact Browser"
                     {:fg :cyan :bold? true}))
      ;; Content + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          ;; Table
          (fn [[tc tr]]
            (if (empty? artifacts)
              (layout/box [tc tr]
                {:title "Artifacts" :border :single :fg :default
                 :content-fn
                 (fn [[ic ir]]
                   (layout/text [ic ir] "  No artifacts available yet."
                                {:fg :default}))})
              (layout/box [tc tr]
                {:title (str "Artifacts (" (count artifacts) ")")
                 :border :single :fg :default
                 :content-fn
                 (fn [[ic ir]]
                   (layout/table [ic ir]
                     {:columns [{:key :type :header "Type" :width 10}
                                {:key :name :header "Name" :width (max 10 (- ic 35))}
                                {:key :size :header "Size" :width 8}
                                {:key :status :header "Status" :width 8}]
                      :data (mapv (fn [a]
                                    {:type (some-> (:type a) name)
                                     :name (or (:name a) (:path a) "unnamed")
                                     :size (get a :size "-")
                                     :status (some-> (:status a) name)})
                                  artifacts)
                      :selected-row selected}))})))
          ;; Footer
          (fn [[fc fr]]
            (layout/text [fc fr]
              " j/k:navigate  Enter:view  Esc:back  1:workflows  q:quit"
              {:fg :default})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
