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
(ns ai.miniforge.buzzword-bingo.report
  "Render a scored scan as text a person reads.

   Ordered so the verdict comes first and the evidence after it: a
   reader who only wants the answer stops at line one, and a reader who
   disagrees with it can see every word that produced it."
  (:require
   [ai.miniforge.buzzword-bingo.format :as fmt]
   [ai.miniforge.buzzword-bingo.messages :as msg]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Vocabulary
(def ^{:stratum 0} ^:private grade-keys
  {:clean :grade/clean :suspect :grade/suspect :slop :grade/slop})

(def ^{:stratum 0} ^:private advice-keys
  {:clean :advice/clean :suspect :advice/suspect :slop :advice/slop})

(def ^{:stratum 0} ^:private term-column-width 22)

(def ^{:stratum 0} ^:private count-column-width 10)

(def ^{:stratum 0} ^:private indent-prefix "  ")

(def ^{:stratum 0} ^:private listed-term-limit
  "Terms listed individually before the tail is summarised. Past this a
   report stops being read and starts being scrolled."
  12)

(defn- ^{:stratum 0} score-line [scored]
  (let [thresholds (:score/thresholds scored)]
    (str (msg/t :report/score-line {:value (fmt/decimal (:score/value scored))})
         " ("
         (msg/t :report/threshold-line
                {:suspect (fmt/decimal (:threshold/suspect thresholds))
                 :slop    (fmt/decimal (:threshold/slop thresholds))})
         ")")))

(defn- ^{:stratum 0} tally-line [scored]
  (let [hits (:score/hit-count scored)]
    (msg/counted :report/tally-one :report/tally-many hits
                 {:hits     hits
                  :words    (:scan/word-count scored)
                  :weighted (:score/weighted scored)})))

(defn- ^{:stratum 0} catalog-line [scored]
  (if-let [version (:scan/lexicon-version scored)]
    (msg/t :report/catalog-line {:version version})
    (msg/t :report/catalog-custom)))

(defn- ^{:stratum 0} times [n]
  (msg/counted :report/count-one :report/count-many n {:count n}))

;------------------------------------------------------------------------------ Layer 1

;; Sections
(defn- ^{:stratum 1} verdict-line [scored]
  (str (msg/t :report/heading) " — " (msg/t (get grade-keys (:score/grade scored)))))

(defn- ^{:stratum 1} category-line [[category tally]]
  (str indent-prefix
       (fmt/pad term-column-width (name category))
       (fmt/pad count-column-width (times (:tally/count tally)))
       (:tally/weighted tally)))

(defn- ^{:stratum 1} term-line [tally]
  (str indent-prefix
       (fmt/pad term-column-width (:tally/term tally))
       (fmt/pad count-column-width (times (:tally/count tally)))
       (name (:tally/category tally))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} scan-report
  "Render `scored`, the result of a scan, as plain text."
  [scored]
  (let [header     [(verdict-line scored) (score-line scored)
                    (tally-line scored) (catalog-line scored)]
        categories (into [(msg/t :report/by-category)]
                         (map category-line)
                         (sort-by key (:score/by-category scored)))
        tallies    (:score/by-term scored)
        listed     (take listed-term-limit tallies)
        hidden     (- (count tallies) (count listed))
        terms      (cond-> (into [(msg/t :report/by-term)] (map term-line) listed)
                     (pos? hidden)
                     (conj (str indent-prefix (msg/t :report/and-more {:count hidden}))))
        body       (if (zero? (:score/hit-count scored))
                     [(msg/t :report/nothing-found)]
                     (concat categories [""] terms))
        advice     (msg/t (get advice-keys (:score/grade scored)))]
    (str/join "\n" (concat header [""] body ["" advice]))))

(comment
  (scan-report {:score/grade :slop :score/value 62.02 :score/hit-count 4
                :score/weighted 8 :scan/word-count 29
                :score/thresholds {:threshold/suspect 10.0 :threshold/slop 25.0}
                :score/by-category {} :score/by-term []}))
