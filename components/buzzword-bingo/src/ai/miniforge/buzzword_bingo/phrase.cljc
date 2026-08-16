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
(ns ai.miniforge.buzzword-bingo.phrase
  "Compile an English term or phrase into a pattern that matches it.

   Handles the three things a literal search gets wrong on prose: word
   boundaries, phrases broken across a wrapped line, and inflected
   forms of the same word."
  (:require
   [ai.miniforge.buzzword-bingo.regex :as regex]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Boundary and inflection vocabulary
(def ^{:stratum 0} ^:private whitespace-run
  "Gap between the words of a phrase. A literal space matches any run of
   whitespace so a phrase still matches across a wrapped line."
  "\\s+")

(def ^{:stratum 0} ^:private word-boundary "\\b")

(def ^{:stratum 0} ^:private regular-suffixes
  "Inflections of a word whose stem survives them intact."
  "(?:s|es|ed|ing|ly)?")

(def ^{:stratum 0} ^:private silent-e-suffixes
  "Inflections of a word ending in silent `e`, which the suffix drops:
   leverage inflects to leveraging, not leverageing."
  "(?:e|es|ed|ing|ely)")

(def ^{:stratum 0} ^:private consonant-y-suffixes
  "Inflections of a word ending in consonant + `y`, which becomes `ie`."
  "(?:y|ies|ied|ying)")

(def ^{:stratum 0} ^:private consonant-y-pattern #"[bcdfghjklmnpqrstvwxz]y$")

(def ^{:stratum 0} ^:private shortest-stemmable-word
  "Words shorter than this keep their final letter; stripping it would
   leave a stem matching far more than the word did."
  3)

(defn- ^{:stratum 0} without-last-character [word]
  (subs word 0 (dec (count word))))

;------------------------------------------------------------------------------ Layer 1

;; \b asserts a word/non-word transition, so anchoring a term that already
;; starts or ends with punctuation would demand a character it does not have.
(defn- ^{:stratum 1} boundary-if-word-edge [edge]
  (if (re-find #"[a-z0-9]" edge) word-boundary ""))

;; The final word of a phrase, with its inflections when stemming.
(defn- ^{:stratum 1} final-word-source [word stem?]
  (let [short? (< (count word) shortest-stemmable-word)]
    (cond
      (not stem?)
      (regex/escape word)

      (and (not short?) (str/ends-with? word "e"))
      (str (regex/escape (without-last-character word)) silent-e-suffixes)

      (and (not short?) (re-find consonant-y-pattern word))
      (str (regex/escape (without-last-character word)) consonant-y-suffixes)

      :else
      (str (regex/escape word) regular-suffixes))))

;------------------------------------------------------------------------------ Layer 2

;; Term compilation
;; Inflection belongs to the head of a phrase — low-hanging fruits, not
;; low-hangings fruit — so only the final word stems.
(defn ^{:stratum 2} term-pattern
  "Compile literal `term` into a case-folded whole-word pattern.
   With `:stem?` truthy it also matches inflections of the final word."
  ([term] (term-pattern term nil))
  ([term {:keys [stem?]}]
   (when (str/blank? term)
     (throw (#?(:clj IllegalArgumentException. :cljs js/Error.)
             "term-pattern requires a non-blank term")))
   (let [folded  (regex/ascii-lower (str/trim term))
         words   (str/split folded #"\s+")
         leading (map regex/escape (butlast words))
         final   (final-word-source (last words) stem?)
         body    (str/join whitespace-run (concat leading [final]))
         head    (boundary-if-word-edge (subs folded 0 1))
         tail    (boundary-if-word-edge (subs folded (dec (count folded))))]
     (re-pattern (str head body tail)))))

(comment
  (term-pattern "low hanging fruit")
  (term-pattern "leverage" {:stem? true}))
