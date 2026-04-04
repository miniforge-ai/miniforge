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

(ns ai.miniforge.reporting.views.meta
  "Meta-loop status rendering.

   Renders meta-loop state and optimization information."
  (:require [ai.miniforge.reporting.views.formatting :as fmt]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Meta-loop status rendering

(defn render-meta-loop
  "Render meta-loop dashboard.

   Args:
     meta-status - Map containing:
       :signals              - Sequence of signal maps with :signal/type, :signal/timestamp
       :pending-improvements - Sequence of improvement maps with :improvement/type, :improvement/confidence, :improvement/rationale
       :recent-improvements  - Sequence of applied improvement maps with :improvement/type, :improvement/applied-at

   Returns:
     Formatted string with 3 boxes (RECENT SIGNALS, PENDING IMPROVEMENTS, RECENT IMPROVEMENTS)."
  [meta-status]
  (let [signals (:signals meta-status)
        pending (:pending-improvements meta-status)
        recent (:recent-improvements meta-status)

        ;; Signals section
        signals-section (if (empty? signals)
                          "No recent signals"
                          (str/join "\n"
                                    (map (fn [sig]
                                           (str "- " (fmt/ansi :cyan (name (:signal/type sig)))
                                                " at " (:signal/timestamp sig)))
                                         (take 10 signals))))

        ;; Pending improvements section
        pending-section (if (empty? pending)
                          (fmt/ansi :green "No pending improvements")
                          (str/join "\n"
                                    (map (fn [imp]
                                           (str "- " (fmt/ansi :yellow (name (:improvement/type imp)))
                                                " (confidence: "
                                                (format "%.2f" (:improvement/confidence imp 0.0))
                                                ")\n  "
                                                (:improvement/rationale imp)))
                                         pending)))

        ;; Recent improvements section
        recent-section (if (empty? recent)
                         "No recent improvements"
                         (str/join "\n"
                                   (map (fn [imp]
                                          (str "- " (fmt/ansi :green (name (:improvement/type imp)))
                                               " applied at " (:improvement/applied-at imp)))
                                        (take 10 recent))))

        width 80]

    (str/join "\n\n"
              [(fmt/draw-box (fmt/ansi :bold "RECENT SIGNALS") signals-section width)
               (fmt/draw-box (fmt/ansi :bold "PENDING IMPROVEMENTS") pending-section width)
               (fmt/draw-box (fmt/ansi :bold "RECENT IMPROVEMENTS") recent-section width)])))

(comment
  ;; Test meta-loop status rendering
  (println (render-meta-loop
            {:signals [{:signal/id #uuid "11111111-1111-1111-1111-111111111111"
                       :signal/type :workflow-failed
                       :signal/timestamp 1234567890000}]
             :pending-improvements [{:improvement/id #uuid "22222222-2222-2222-2222-222222222222"
                                    :improvement/type :rule-addition
                                    :improvement/confidence 0.85
                                    :improvement/rationale "Add validation rule"}]
             :recent-improvements []}))

  (println (render-meta-loop
            {:signals []
             :pending-improvements []
             :recent-improvements []}))

  :end)
