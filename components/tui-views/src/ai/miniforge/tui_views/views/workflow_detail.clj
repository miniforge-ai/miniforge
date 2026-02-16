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

(defn- find-workflow [model]
  (let [wf-id (get-in model [:detail :workflow-id])]
    (some #(when (= (:id %) wf-id) %) (:workflows model))))

(defn- status-suffix [status]
  (case status
    :running  " ●"
    :success  " ✓"
    :failed   " ✗"
    ""))

(defn- format-phase-node [{:keys [phase status iteration total-iterations]}]
  {:label (str (name phase) (status-suffix status)
               (when (and iteration total-iterations)
                 (str " [" iteration "/" total-iterations "]")))
   :depth 0
   :expandable? false})

(defn- render-title-bar [theme wf [cols rows]]
  (layout/text [cols rows]
               (str " MINIFORGE │ "
                    (or (:name wf) "Workflow Detail")
                    (when-let [phase (:phase wf)]
                      (str " │ " (name phase))))
               {:fg (get theme :header :cyan) :bold? true}))

(defn- render-phase-list [theme phases [cols rows]]
  (layout/box [cols rows]
    {:title "Phases" :border :single :fg (get theme :border :default)
     :content-fn
     (fn [[ic ir]]
       (if (empty? phases)
         (layout/text [ic ir] "  Waiting for phases...")
         (widget/tree [ic ir]
           {:nodes (mapv format-phase-node phases)
            :selected 0})))}))

(defn- render-agent-output [theme agent output scroll-offset search-matches match-idx [cols rows]]
  (layout/box [cols rows]
    {:title (if agent
              (str (name (:agent agent)) " - " (name (:status agent :idle)))
              "Agent Output")
     :border :single :fg (get theme :border :default)
     :content-fn
     (fn [[ic ir]]
       (let [lines (if (seq output)
                     (str/split-lines output)
                     ["  Waiting for agent output..."])
             max-offset (max 0 (- (count lines) ir))
             ;; nil scroll-offset = auto-scroll to bottom
             offset (if (some? scroll-offset)
                      (min scroll-offset max-offset)
                      max-offset)
             ;; Build set of line indices that have search matches
             hl-lines (when (seq search-matches)
                        (set (map :line-idx search-matches)))
             current-hl (when match-idx
                          (:line-idx (get search-matches match-idx)))]
         (widget/scrollable [ic ir]
           {:lines lines
            :offset offset
            :fg (get theme :fg :default)
            :highlight-lines hl-lines
            :current-highlight current-hl})))}))

(defn- render-footer [theme model [cols rows]]
  (let [matches (:search-matches model)
        match-idx (:search-match-idx model)
        match-info (when (seq matches)
                     (str "MATCH [" (inc (or match-idx 0)) "/"
                          (count matches) "] │ "))]
    (layout/text [cols rows]
      (str " " (or match-info "")
           "j/k:scroll  /:search  n/N:next/prev  Tab:cycle  Esc:back  q:quit")
      {:fg (get theme :fg-dim :default)})))

(defn render
  "Render the workflow detail view.
   model: full app model
   [cols rows]: available screen area"
  [model [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        wf (find-workflow model)
        detail (:detail model)
        phases (:phases detail)
        agent (:current-agent detail)
        output (:agent-output detail)
        scroll-offset (:scroll-offset model)
        search-matches (:search-matches model)
        match-idx (:search-match-idx model)]
    (layout/split-v [cols rows] (/ 2.0 rows)
      (fn [size] (render-title-bar theme wf size))
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [[mc mr]]
            (layout/split-h [mc mr] 0.35
              ;; Left panel: intent box (5 rows) + phases
              (fn [[lc lr]]
                (layout/split-v [lc lr] (/ 5.0 lr)
                  ;; Intent box
                  (fn [[ic ir]]
                    (layout/box [ic ir]
                      {:title "Intent" :border :single :fg (get theme :border :default)
                       :content-fn
                       (fn [[bc br]]
                         (let [intent (get-in model [:detail :evidence :intent])
                               intent-type (or (some-> intent :type name) "task")
                               desc (or (:description intent) "No intent")]
                           (layout/text [bc br]
                             (str intent-type ": " desc)
                             {:fg (get theme :fg :default)})))}))
                  ;; Phase tree
                  (fn [size] (render-phase-list theme phases size))))
              (fn [size] (render-agent-output theme agent output scroll-offset
                                              search-matches match-idx size))))
          (partial render-footer theme model))))))

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
