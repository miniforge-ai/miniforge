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
(ns ai.miniforge.buzzword-bingo.regex
  "Pattern primitives that behave identically on JVM, Babashka and Node.

   Everything host-specific about regular expressions is confined here:
   match positions, case folding and escaping. Patterns built on top of
   these are matched against pre-folded lowercase text, since
   JavaScript has no inline `(?i)`, `(?s)` or `(?m)` modifiers."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Folding, escaping, match enumeration
;; Not clojure.string/lower-case: that is not length-preserving across all of
;; Unicode (U+0130 lowercases to two codepoints), and every line and column the
;; scanner reports is an index into the folded string.
(defn ^{:stratum 0} ascii-lower
  "Lowercase the ASCII letters of `s`, leaving other codepoints untouched.
   Preserves length, and therefore index positions."
  [s]
  (str/replace s #"[A-Z]" str/lower-case))

(def ^{:stratum 0} ^:private metacharacter-pattern
  "Characters escaped to match literally. Limited to the punctuation both
   Java and JavaScript accept escaped; escaping more throws on the JVM."
  #"[.*+?^${}()|\[\]\\]")

(defn ^{:stratum 0} find-all
  "Every non-overlapping match of `pattern` in `s`, in source order, as
   `{:match/index i :match/text t}` where `i` is an offset into `s`."
  [pattern s]
  #?(:clj
     (let [matcher (re-matcher pattern s)]
       (loop [acc []]
         (if (.find matcher)
           (recur (conj acc {:match/index (.start matcher)
                             :match/text  (.group matcher)}))
           acc)))

     :cljs
     ;; Cloned rather than reused so enumeration cannot leave lastIndex set on
     ;; a caller's pattern; the clone keeps the original flags, since dropping
     ;; them would make the same pattern behave differently from the JVM.
     ;; A zero-width match leaves lastIndex where it was, so advance it by hand
     ;; or .exec never terminates.
     (let [flags (.-flags pattern)
           re    (js/RegExp. (.-source pattern)
                             (if (str/includes? flags "g") flags (str flags "g")))]
       (loop [acc []]
         (if-let [m (.exec re s)]
           (let [text (aget m 0)]
             (when (zero? (count text))
               (set! (.-lastIndex re) (inc (.-lastIndex re))))
             (recur (conj acc {:match/index (.-index m)
                               :match/text  text})))
           acc)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} escape
  "Escape `s` so it matches itself when embedded in a pattern."
  [s]
  (str/replace s metacharacter-pattern #(str "\\" %)))

(comment
  (ascii-lower "Comprehensive İ")
  (escape "c++")
  (find-all #"ab" "abcab"))
