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
(ns ai.miniforge.buzzword-bingo.score
  "Turn hits into a number a gate can act on.

   A raw count is not comparable across documents, so the reported
   figure is a rate per thousand words, weighted by how damning each
   term is."
  (:require
   [ai.miniforge.buzzword-bingo.tally :as tally]))

;------------------------------------------------------------------------------ Layer 0

;; Constants and arithmetic
;; Worked cases behind the numbers, at the smoothing below: one marketing
;; adjective in 500 words scores 5, two score 10, five score 25; a single
;; filler word in a ten-word reply scores 9, a single marketing adjective in
;; the same reply scores 27. Catching the last of those is the point of the
;; tool, so a short text is not exempt — only a light one is.
(def ^{:stratum 0} default-thresholds
  "Grade boundaries in weighted hits per thousand words. Calibrated by
   hand against generated and hand-written prose, not derived — a
   working number, tuned as evidence arrives."
  {:threshold/suspect 10.0
   :threshold/slop    25.0})

(def ^{:stratum 0} ^:private density-smoothing-words
  "Words added to every rate's denominator. Without it a two-sentence
   reply with one buzzword outscores a long document with twenty."
  100)

(def ^{:stratum 0} ^:private per-thousand 1000)

;; Truncating after adding a half rounds half up for non-negatives on both
;; hosts, avoiding a rounding function ClojureScript does not share.
(defn- ^{:stratum 0} round-hundredths [x]
  (/ (long (+ 0.5 (* x 100))) 100.0))

(defn- ^{:stratum 0} grade-for [value thresholds]
  (cond
    (>= value (get thresholds :threshold/slop))    :slop
    (>= value (get thresholds :threshold/suspect)) :suspect
    :else                                          :clean))

;------------------------------------------------------------------------------ Layer 1

;; Rates
(defn- ^{:stratum 1} rate [total word-count]
  (round-hundredths
   (/ (* per-thousand (double total))
      (+ word-count density-smoothing-words))))

;------------------------------------------------------------------------------ Layer 2

;; Summary
(defn ^{:stratum 2} summarize
  "Score a scan result, returning it augmented with `:score/*` keys:
   counts, per-thousand-word rates, per-category and per-term tallies,
   and a grade of `:clean`, `:suspect` or `:slop`.

   `:thresholds` in `opts` overrides grade boundaries; it is merged
   over the defaults, so supplying one boundary leaves the other in
   place rather than comparing against nothing."
  ([scan] (summarize scan nil))
  ([scan opts]
   (let [thresholds (merge default-thresholds (:thresholds opts))
         hits       (get scan :scan/hits)
         words      (get scan :scan/word-count 0)
         weighted   (tally/weight-of hits)
         value      (rate weighted words)]
     (merge scan
            {:score/hit-count   (count hits)
             :score/weighted    weighted
             :score/density     (rate (count hits) words)
             :score/value       value
             :score/grade       (grade-for value thresholds)
             :score/thresholds  thresholds
             :score/by-category (tally/by-category hits)
             :score/by-term     (tally/by-term hits)}))))

(comment
  (summarize {:scan/hits [{:hit/id :robust :hit/term "robust"
                           :hit/category :marketing :hit/weight 3}]
              :scan/word-count 40}))
