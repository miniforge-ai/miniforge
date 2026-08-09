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
(ns ai.miniforge.codex.pegs
  "Per-peg views of a consultation (Codex SPEC T1 §7.7).

   A consultation PRESENTS every discriminator (peg) reachable from its
   situation. Recording, per presented peg, which way it was answered and
   the landing set behind each answer is the only data the §4.4.1
   retirement trigger (answer-distribution entropy, routing relevance)
   can compute over — so the consider response carries that basis."
  (:require [ai.miniforge.codex.graph :as graph]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} answer-landings
  "Landing ids (problems/resolutions) reachable from one answer's target,
   sorted for stable telemetry."
  [nodes target]
  (->> (graph/reachable-from nodes target)
       (keep nodes)
       (filter #(#{"problem" "resolution"} (:type %)))
       (map :id)
       sort
       vec))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} peg-views
  "The §7.7 per-peg telemetry basis: every discriminator among `reachable`
   (id-sorted), each with the landing set every answer routes to."
  [nodes reachable]
  (->> reachable
       (keep nodes)
       (filter #(= "discriminator" (:type %)))
       (sort-by :id)
       (mapv (fn [peg]
               {:id (:id peg)
                :answers (into {}
                               (for [r (graph/as-edge-list (:routes-to peg))
                                     :let [target (or (:target r) (:value r))]
                                     :when target]
                                 [(:answer r) (answer-landings nodes target)]))}))))
