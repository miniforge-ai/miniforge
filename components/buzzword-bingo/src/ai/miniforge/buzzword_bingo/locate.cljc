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
(ns ai.miniforge.buzzword-bingo.locate
  "Turn a character offset into somewhere a human can look.

   Matching happens on a masked copy of a document; reporting has to
   name a place in the original. Both strings have the same length by
   construction, so one offset serves for both."
  (:require
   [ai.miniforge.buzzword-bingo.regex :as regex]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Offsets
(def ^{:stratum 0} ^:private newline-pattern #"\n")

(def ^{:stratum 0} ^:private snippet-radius
  "Characters of surrounding source quoted on either side of a hit."
  40)

;; Linear in lines per lookup, which is well inside budget for documents and
;; chat turns; revisit before running this over a whole repository in one pass.
(defn- ^{:stratum 0} position-of [starts index]
  (let [line (count (take-while #(<= % index) starts))]
    {:hit/line   line
     :hit/column (inc (- index (nth starts (dec line))))}))

;------------------------------------------------------------------------------ Layer 1

;; Lines and quotations
(defn ^{:stratum 1} line-starts
  "Character offset at which each line of `text` begins."
  [text]
  (into [0] (map #(inc (:match/index %))) (regex/find-all newline-pattern text)))

(defn- ^{:stratum 1} snippet [text index length]
  (let [from (max 0 (- index snippet-radius))
        to   (min (count text) (+ index length snippet-radius))]
    (-> (subs text from to)
        (str/replace #"\s+" " ")
        str/trim)))

;------------------------------------------------------------------------------ Layer 2

;; Placing a hit
(defn ^{:stratum 2} place
  "Attach one-based line and column, and surrounding source, to `hit`.

   `starts` comes from `line-starts` over the same `text`; it is passed
   in rather than recomputed so a scan pays for it once."
  [text starts hit]
  (merge hit
         (position-of starts (:hit/index hit))
         {:hit/context (snippet text (:hit/index hit) (:hit/length hit))}))

(comment
  (line-starts "one\ntwo\nthree")
  (place "a robust claim" (line-starts "a robust claim")
         {:hit/index 2 :hit/length 6}))
