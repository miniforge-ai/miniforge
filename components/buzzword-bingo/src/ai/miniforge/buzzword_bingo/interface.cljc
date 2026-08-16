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
(ns ai.miniforge.buzzword-bingo.interface
  "Public API for the buzzword-bingo component.

   Counts marketing, corporate and generated-prose tells in a document
   and grades the result, so a caller can decide whether prose is worth
   keeping or should be written again. A session plays the same hits
   onto a bingo card across the turns of one conversation."
  (:require
   [ai.miniforge.buzzword-bingo.card :as card]
   [ai.miniforge.buzzword-bingo.detect :as detect]
   [ai.miniforge.buzzword-bingo.lexicon :as lexicon]
   [ai.miniforge.buzzword-bingo.score :as score]
   [ai.miniforge.buzzword-bingo.segment :as segment]
   [ai.miniforge.buzzword-bingo.session :as session]))

;------------------------------------------------------------------------------ Layer 0

;; Catalog
(defn ^{:stratum 0} entries
  "Every compiled lexicon entry."
  []
  lexicon/entries)

(defn ^{:stratum 0} categories
  "Category key → attributes, including the category's default weight."
  []
  lexicon/categories)

(defn ^{:stratum 0} lexicon-version
  "Version stamp of the catalog backing this build."
  []
  lexicon/version)

(defn ^{:stratum 0} default-thresholds
  "Grade boundaries applied when a caller supplies none."
  []
  score/default-thresholds)

;; Scanning
(defn ^{:stratum 0} prose-only
  "Return `text` with code, links, paths and quotations blanked out.

   The result is the same length as `text`, so offsets into it address
   the source. `:score-quotes?` in `opts` keeps blockquoted lines."
  ([text] (segment/prose-only text))
  ([text opts] (segment/prose-only text opts)))

(defn ^{:stratum 0} scan
  "Scan `text` for lexicon terms and grade the result.

   Returns hits with position and context, per-category and per-term
   tallies, a weighted rate per thousand words, and a grade of
   `:clean`, `:suspect` or `:slop`.

   Options: `:entries` replaces the catalog, `:thresholds` replaces the
   grade boundaries, `:score-quotes?` counts blockquoted lines."
  ([text] (scan text nil))
  ([text opts] (score/summarize (detect/scan text opts) opts)))

;; Sessions
(defn ^{:stratum 0} deal-card
  "Deal the bingo card `seed` selects from the catalog.

   Deterministic: the same seed and catalog always deal the same card,
   so a card can be rebuilt from its seed rather than stored."
  ([seed] (card/card-for seed lexicon/entries))
  ([seed catalog-entries] (card/card-for seed catalog-entries)))

(defn ^{:stratum 0} winning-lines
  "Square indices of the twelve lines that win."
  []
  session/winning-lines)

(defn ^{:stratum 0} open-session
  "Start a session playing the card `seed` deals."
  ([seed] (session/open seed lexicon/entries))
  ([seed catalog-entries] (session/open seed catalog-entries)))

(defn ^{:stratum 0} track
  "Fold one `scan` result into `session`, returning it advanced a turn.

   `:session/new-lines` holds only the lines this turn completed, so a
   caller announcing BINGO announces each line once. Both it and
   `:session/lines` are sets of indices into `winning-lines`, not
   square indices and not the line vectors themselves."
  [session scan-result]
  (session/track session scan-result))

(comment
  (prose-only "Use `robust` in code but not in prose.")
  (scan "A robust, comprehensive, seamless solution.")
  (:score/grade (scan "Plain sentence about a file parser."))
  (-> (open-session "demo") (track (scan "A robust and seamless solution."))))
