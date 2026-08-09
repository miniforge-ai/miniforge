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
(ns ai.miniforge.buzzword-bingo.detect
  "Find every catalog term in a document.

   Matching runs over a masked, case-folded copy so code and links
   cannot score; positions and context come from the source."
  (:require
   [ai.miniforge.buzzword-bingo.lexicon :as lexicon]
   [ai.miniforge.buzzword-bingo.locate :as locate]
   [ai.miniforge.buzzword-bingo.regex :as regex]
   [ai.miniforge.buzzword-bingo.segment :as segment]))

;------------------------------------------------------------------------------ Layer 0

;; Raw hits
(def ^{:stratum 0} ^:private word-pattern
  "A prose word: alphanumeric with internal apostrophes and hyphens."
  #"[a-z0-9][a-z0-9'’-]*")

(defn- ^{:stratum 0} hit-of [entry {:match/keys [index text]}]
  {:hit/id       (:entry/id entry)
   :hit/term     (:entry/display entry)
   :hit/matched  text
   :hit/category (:entry/category entry)
   :hit/weight   (:entry/effective-weight entry)
   :hit/index    index
   :hit/length   (count text)})

;; Earliest first, longest first at equal position — the order keep-outermost
;; relies on.
(defn- ^{:stratum 0} hit-order [hit]
  [(:hit/index hit) (- (:hit/length hit))])

;; Entries overlap by design (`deep dive` contains `dive`) and counting both
;; charges a document twice for one phrase. Every kept hit starts at or before
;; the candidate, so a candidate ending no later than the furthest end so far
;; is contained in one of them.
(defn- ^{:stratum 0} keep-outermost [{:keys [kept furthest-end]} hit]
  (let [end (+ (:hit/index hit) (:hit/length hit))]
    (if (<= end furthest-end)
      {:kept kept :furthest-end furthest-end}
      {:kept (conj kept hit) :furthest-end end})))

;------------------------------------------------------------------------------ Layer 1

;; Counting and matching
(defn ^{:stratum 1} word-count
  "Count prose words in already-masked, already-folded text."
  [folded]
  (count (regex/find-all word-pattern folded)))

(defn- ^{:stratum 1} entry-hits [folded entry]
  (map (partial hit-of entry) (regex/find-all (:entry/regex entry) folded)))

;------------------------------------------------------------------------------ Layer 2

;; Scanning
(defn ^{:stratum 2} scan
  "Locate every lexicon hit in `text`.

   Returns `{:scan/hits [...] :scan/word-count n :scan/lexicon-version v}`
   with hits ordered by position. `:entries` in `opts` replaces the
   catalog; remaining options pass through to masking."
  ([text] (scan text nil))
  ([text opts]
   (let [entries (get opts :entries lexicon/entries)
         folded  (regex/ascii-lower (segment/prose-only text opts))
         starts  (locate/line-starts text)
         raw     (sort-by hit-order (mapcat (partial entry-hits folded) entries))
         kept    (:kept (reduce keep-outermost {:kept [] :furthest-end -1} raw))]
     {:scan/hits            (mapv (partial locate/place text starts) kept)
      :scan/word-count      (word-count folded)
      :scan/lexicon-version lexicon/version})))

(comment
  (scan "This robust and comprehensive solution will delve into synergy.")
  (:scan/word-count (scan "one two three")))
