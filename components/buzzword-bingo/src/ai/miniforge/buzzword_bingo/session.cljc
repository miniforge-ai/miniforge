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
(ns ai.miniforge.buzzword-bingo.session
  "Play a card across many turns of one conversation.

   A session is a value: each turn's scan produces a new session rather
   than mutating one, so the caller holding it decides where state
   lives."
  (:require
   [ai.miniforge.buzzword-bingo.card :as card]))

;------------------------------------------------------------------------------ Layer 0

;; The board
;; Written out rather than computed: the geometry of a bingo card is worth
;; being able to read, and a literal cannot drift from the card it describes.
(def ^{:stratum 0} winning-lines
  "Square indices of the five rows, five columns and two diagonals."
  [[0 1 2 3 4]      [5 6 7 8 9]      [10 11 12 13 14]
   [15 16 17 18 19] [20 21 22 23 24]
   [0 5 10 15 20]   [1 6 11 16 21]   [2 7 12 17 22]
   [3 8 13 18 23]   [4 9 14 19 24]
   [0 6 12 18 24]   [4 8 12 16 20]])

(defn ^{:stratum 0} open
  "Start a session on the card `seed` deals from `entries`."
  [seed entries]
  {:session/id         seed
   :session/card       (card/card-for seed entries)
   :session/marked     #{}
   :session/lines      #{}
   :session/turns      0
   :session/hit-count  0
   :session/weighted   0
   :session/word-count 0})

;; Marking
(defn- ^{:stratum 0} marked-indices
  "Square indices covered by `marked` ids, the free square included."
  [card marked]
  (into #{card/free-square-index}
        (comp (filter #(contains? marked (:square/id %)))
              (map :square/index))
        (:card/squares card)))

(defn- ^{:stratum 0} line-index-when-covered [covered line-index line]
  (when (every? covered line) line-index))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} lines-completed [card marked]
  (let [covered (marked-indices card marked)]
    (into #{} (keep-indexed (partial line-index-when-covered covered)) winning-lines)))

;------------------------------------------------------------------------------ Layer 2

;; Turns
(defn ^{:stratum 2} track
  "Fold one `scan` result into `session`.

   Returns the session advanced by a turn, with `:session/new-lines`
   holding only the lines this turn completed — a caller shouts BINGO
   on that, so one line is announced once however many turns follow."
  [session scan]
  (let [marked (into (:session/marked session) (map :hit/id) (:scan/hits scan))
        lines  (lines-completed (:session/card session) marked)
        fresh  (into #{} (remove (:session/lines session)) lines)]
    (assoc session
           :session/marked     marked
           :session/lines      lines
           :session/new-lines  fresh
           :session/bingo?     (boolean (seq lines))
           :session/turns      (inc (:session/turns session))
           :session/hit-count  (+ (:session/hit-count session) (count (:scan/hits scan)))
           :session/weighted   (+ (:session/weighted session) (get scan :score/weighted 0))
           :session/word-count (+ (:session/word-count session) (get scan :scan/word-count 0)))))

(comment
  (open "session-1" [])
  (track (open "session-1" []) {:scan/hits [] :scan/word-count 10}))
