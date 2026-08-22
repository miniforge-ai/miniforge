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
(ns ai.miniforge.buzzword-bingo.format
  "Render numbers and columns the same way on every host.

   `clojure.core/format` does not exist in ClojureScript, and `str` on
   a double disagrees across hosts — 25.0 prints as \"25.0\" on the JVM
   and \"25\" in JavaScript. Both would show up as a diff between the
   Babashka CLI and the Node build reporting the same scan.")

;------------------------------------------------------------------------------ Layer 0

;; Numbers and columns
(def ^{:stratum 0} ^:private hundredths 100)

(defn ^{:stratum 0} pad
  "Pad `s` with spaces to `width`, leaving it alone when already longer."
  [width s]
  (let [text (str s)]
    (if (>= (count text) width)
      text
      (str text (apply str (repeat (- width (count text)) " "))))))

(def ^{:stratum 0} ^:private ellipsis "…")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} decimal
  "Render non-negative `x` with exactly two decimal places."
  [x]
  (let [scaled (long (+ 0.5 (* x hundredths)))
        whole  (quot scaled hundredths)
        frac   (rem scaled hundredths)]
    (str whole "." (when (< frac 10) "0") frac)))

(defn ^{:stratum 1} truncate
  "Shorten `s` to `width`, ending it with an ellipsis when cut."
  [width s]
  (let [text (str s)]
    (if (<= (count text) width)
      text
      (str (subs text 0 (dec width)) ellipsis))))

(comment
  (decimal 62.015)
  (decimal 25.0)
  (pad 10 "robust")
  (truncate 8 "low-hanging fruit"))
