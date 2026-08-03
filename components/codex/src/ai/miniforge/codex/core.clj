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
(ns ai.miniforge.codex.core
  "consider(situation) -> {landings, coverage}  (Codex SPEC T1 §7.1).

   Assembles a response from the node graph (ai.miniforge.codex.node) and
   the graph views (ai.miniforge.codex.graph). Anomalies are data: an
   unreadable codex, an unmatched or an ambiguous situation returns a map
   with :codex/anomaly, never an empty success — an empty answer that looks
   like a clean answer is the exact failure the codex exists to prevent."
  (:require [ai.miniforge.codex.graph :as graph]
            [ai.miniforge.codex.messages :as msg]
            [ai.miniforge.codex.node :as node]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} consider
  "Route `situation-text` through the codex at `codex-dir`.

   Returns {:situation id
            :landings [...ordered strategic -> operational -> tactical]
            :coverage {...}}
   or a {:codex/anomaly ...} map when the codex is unreadable, the situation
   matches nothing, or it matches ambiguously. Unmatched is an ANSWER, not
   an empty list — silence about a missing match is false coverage."
  [codex-dir situation-text]
  (when (str/blank? (str situation-text))
    (throw (IllegalArgumentException. "situation-text must be non-blank")))
  (let [{:keys [nodes] :as loaded} (node/load-graph codex-dir)]
    (if (:codex/anomaly loaded)
      loaded
      (let [matches (graph/match-situations nodes situation-text)]
        (cond
          (empty? matches)
          {:codex/anomaly :situation-unmatched
           :codex/reason (msg/t :anomaly/unmatched {:text situation-text})
           ;; sorted: (vals nodes) order is map-order, and an anomaly that
           ;; lists candidates should list them stably
           :codex/available (->> (vals nodes)
                                 (filter #(= "situation" (:type %)))
                                 (sort-by :id)
                                 (mapv (juxt :id :title)))}

          (< 1 (count matches))
          {:codex/anomaly :situation-ambiguous
           :codex/reason (msg/t :anomaly/ambiguous
                                {:text situation-text :count (count matches)})
           :codex/matches (->> matches
                               (sort-by :id)
                               (mapv (juxt :id :title)))}

          :else
          (let [situation (first matches)
                landings (->> (graph/reachable-from nodes (:id situation))
                              (keep nodes)
                              (filter #(#{"problem" "resolution"} (:type %)))
                              (map #(graph/landing-view nodes %))
                              (sort-by (fn [l] [(get graph/horizon-rank (:horizon l) 99)
                                                (:id l)]))
                              vec)]
            {:situation (:id situation)
             :situation-title (:title situation)
             :landings landings
             :coverage (graph/coverage-of landings)}))))))
