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
(ns ai.miniforge.buzzword-bingo.entry
  "Turn an authored catalog entry into one the scanner can match with.

   An authored entry carries a term or a raw pattern and a category; a
   compiled one adds a stable id, a display name, the weight in force
   and the compiled pattern."
  (:require
   [ai.miniforge.buzzword-bingo.phrase :as phrase]
   [ai.miniforge.buzzword-bingo.regex :as regex]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Reading one entry
;; A display name with no ASCII alphanumerics at all slugs to the empty
;; string. Two such entries would share the blank id and silently merge in the
;; per-term tallies, which group on it, so the catalog is refused instead.
(defn- ^{:stratum 0} slug-id [s]
  (let [slug (-> (regex/ascii-lower s)
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-" "")
                 (str/replace #"-$" ""))]
    (when (str/blank? slug)
      (throw (#?(:clj IllegalArgumentException. :cljs js/Error.)
              (str "Lexicon entry has no identifier-forming characters: " (pr-str s)))))
    (keyword slug)))

;; An entry supplies either a literal term or raw pattern source; the raw form
;; exists for shapes with no single literal, such as a variable middle.
(defn- ^{:stratum 0} entry-pattern [{:entry/keys [term pattern stem?]}]
  (if pattern
    (re-pattern pattern)
    (phrase/term-pattern term {:stem? stem?})))

;; A catalog is authored, not received at runtime, so a bad entry is a
;; programmer error caught at load rather than data to be handled.
(defn- ^{:stratum 0} weight-for [categories category]
  (let [defaults (get categories category)]
    (when-not defaults
      (throw (#?(:clj IllegalArgumentException. :cljs js/Error.)
              (str "Unknown lexicon category: " category))))
    (get defaults :category/weight)))

(defn- ^{:stratum 0} display-for [entry]
  (let [display (or (:entry/label entry) (:entry/term entry))]
    (when (str/blank? display)
      (throw (#?(:clj IllegalArgumentException. :cljs js/Error.)
              (str "Lexicon entry needs a term or a label: " (pr-str entry)))))
    display))

;------------------------------------------------------------------------------ Layer 1

;; Compilation
(defn- ^{:stratum 1} compile-one [categories entry]
  (let [display (display-for entry)
        weight  (get entry :entry/weight
                     (weight-for categories (get entry :entry/category)))]
    (assoc entry
           :entry/id (slug-id display)
           :entry/display display
           :entry/effective-weight weight
           :entry/regex (entry-pattern entry))))

;------------------------------------------------------------------------------ Layer 2

;; Compiling a catalog
(defn ^{:stratum 2} compile-all
  "Compile `raw-entries` against `categories`.

   Public so a caller can supply a project-local catalog in the shape
   of the shipped one and get entries the scanner accepts."
  [categories raw-entries]
  (mapv (partial compile-one categories) raw-entries))

(comment
  (compile-all {:mild {:category/weight 1}}
               [{:entry/term "low-hanging fruit" :entry/category :mild}]))
