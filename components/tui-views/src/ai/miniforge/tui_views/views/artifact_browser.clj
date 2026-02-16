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
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.views.tab-bar :as tab-bar])
  (:import
   [java.text SimpleDateFormat]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- format-time [ts]
  (when ts
    (try
      (.format (SimpleDateFormat. "HH:mm:ss") ts)
      (catch Exception _ ""))))

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn- render-footer [model [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        sel-count (count (:selected-ids model))
        visual? (:visual-anchor model)
        flash (:flash-message model)]
    (layout/text [cols rows]
      (cond
        (pos? sel-count)
        (str " " sel-count " selected │ Space:toggle  v:visual  a:all  c:clear"
             (when flash (str "  │ " flash)))

        visual?
        (str " VISUAL │ j/k:extend  Space:toggle  Esc:exit  a:all"
             (when flash (str "  │ " flash)))

        :else
        (str " j/k:navigate  Space:select  /:search  Tab:cycle  ←→:prev/next  Esc:back  q:quit"
             (when flash (str "  │ " flash))))
      {:fg (get theme :fg-dim :default)})))

(defn render
  "Render the artifact browser.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        artifacts (get-in model [:detail :artifacts] [])
        selected (:selected-idx model)
        selected-ids (or (:selected-ids model) #{})]
    (layout/split-v [cols rows] (/ 2.0 rows)
      ;; Tab bar
      (fn [[c r]]
        (let [wf-id (get-in model [:detail :workflow-id])
              wf-name (some #(when (= (:id %) wf-id) (:name %))
                            (:workflows model))]
          (tab-bar/render model (or wf-name "Artifacts") [c r])))
      ;; Content + footer
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          ;; Table
          (fn [[tc tr]]
            (if (empty? artifacts)
              (layout/box [tc tr]
                {:title "Artifacts" :border :single :fg (get theme :border :default)
                 :content-fn
                 (fn [[ic ir]]
                   (layout/text [ic ir] "  No artifacts available yet."
                                {:fg (get theme :fg :default)}))})
              (let [show-sel? (seq selected-ids)
                    sel-w (if show-sel? 3 0)]
                (layout/box [tc tr]
                  {:title (str "Artifacts (" (count artifacts) ")")
                   :border :single :fg (get theme :border :default)
                   :content-fn
                   (fn [[ic ir]]
                     (let [data (mapv (fn [idx a]
                                        (cond->
                                          {:type (some-> (:type a) name)
                                           :name (or (:name a) (:path a) "unnamed")
                                           :phase (some-> (:phase a) name)
                                           :size (or (:size a) "-")
                                           :status (some-> (:status a) name)
                                           :time (or (format-time (:created-at a)) "")}
                                          show-sel?
                                          (assoc :sel (if (contains? selected-ids [:artifact idx])
                                                        "[x]" "[ ]"))))
                                      (range) artifacts)
                           columns (cond-> []
                                     show-sel? (conj {:key :sel :header "   " :width 3})
                                     true (into [{:key :type :header "Type" :width 10}
                                                 {:key :name :header "Name" :width (max 10 (- ic 55 sel-w))}
                                                 {:key :phase :header "Phase" :width 10}
                                                 {:key :size :header "Size" :width 8}
                                                 {:key :status :header "Status" :width 8}
                                                 {:key :time :header "Time" :width 10}]))]
                       (layout/table [ic ir]
                         {:columns columns :data data :selected-row selected
                          :header-fg (get theme :header :cyan)
                          :row-fg (get theme :row-fg :default)
                          :row-bg (get theme :row-bg :default)
                          :selected-fg (get theme :selected-fg :white)
                          :selected-bg (get theme :selected-bg :blue)})))}))))
          ;; Footer
          (fn [size] (render-footer model size)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
