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
(ns ai.miniforge.buzzword-bingo.lexicon
  "The shipped catalog of counted terms.

   Canonical form is EDN on disk so it reviews as data. It is inlined
   at compile time rather than read at runtime so one namespace serves
   both a JVM classpath and a bundled Node script."
  (:require
   [ai.miniforge.buzzword-bingo.entry :as entry]
   #?(:clj [ai.miniforge.buzzword-bingo.inline :as inline]))
  #?(:cljs (:require-macros [ai.miniforge.buzzword-bingo.inline :as inline])))

;------------------------------------------------------------------------------ Layer 0

;; Catalog source
;; The macro needs a literal path, so it cannot come from a named constant.
(def ^{:stratum 0} ^:private source
  (inline/edn-resource "config/buzzword-bingo/lexicon.edn"))

;------------------------------------------------------------------------------ Layer 1

;; Published catalog
(def ^{:stratum 1} categories
  "Category key → attributes, including the category's default weight."
  (get source :lexicon/categories))

(def ^{:stratum 1} version
  "Catalog version stamp, reported with every scan so a score traces
   back to the catalog that produced it."
  (get source :lexicon/version))

;------------------------------------------------------------------------------ Layer 2

;; Compiled entries
(def ^{:stratum 2} entries
  "Every catalog entry, compiled and ready to match."
  (entry/compile-all categories (get source :lexicon/entries)))

(comment
  (count entries)
  (first entries))
