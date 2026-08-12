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
(ns ai.miniforge.buzzword-bingo.board
  "Render a session's card as a five-by-five grid of text.

   Marked squares are bracketed rather than coloured, so the board
   survives a pipe, a log file and a terminal without colour."
  (:require
   [ai.miniforge.buzzword-bingo.card :as card]
   [ai.miniforge.buzzword-bingo.format :as fmt]
   [ai.miniforge.buzzword-bingo.messages :as msg]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Cells
(def ^{:stratum 0} ^:private cell-width 17)

(def ^{:stratum 0} ^:private row-length 5)

(def ^{:stratum 0} ^:private row-prefix "  ")

;; The board
;; Counts the marks that landed on this card, not every term the session has
;; seen: most of the catalog is not on any one board, and reporting the wider
;; number next to a 24-square total would overstate how full the card is.
(defn- ^{:stratum 0} squares-marked [session]
  (let [marked (:session/marked session)]
    (count (filter marked (card/ids-on (:session/card session))))))

;------------------------------------------------------------------------------ Layer 1

;; The grid
;; Two characters go around every label, so the label itself gets two fewer
;; than the cell — which keeps every cell exactly `cell-width` wide whether it
;; is bracketed or not, and the columns therefore line up.
(defn- ^{:stratum 1} cell-text [marked square]
  (let [free?   (:square/free? square)
        label   (if free?
                  (msg/t :card/free)
                  (fmt/truncate (- cell-width 2) (:square/term square)))
        wrapped (if (or free? (contains? marked (:square/id square)))
                  (str "[" label "]")
                  (str " " label " "))]
    (fmt/pad cell-width wrapped)))

(defn- ^{:stratum 1} progress-lines [session]
  (let [lines (count (:session/lines session))
        turns (:session/turns session)]
    [(str (msg/counted :card/turns-one :card/turns-many turns {:turns turns})
          ", "
          (msg/t :card/squares {:marked (squares-marked session)
                                :total  (dec card/square-count)}))
     (when (pos? lines)
       (str (msg/t :card/bingo) " "
            (msg/counted :card/line-one :card/line-many lines {:count lines})))]))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} board-report
  "Render `session` as a heading, the card, and where the game stands."
  [session]
  (let [marked  (:session/marked session)
        cells   (map (partial cell-text marked)
                     (:card/squares (:session/card session)))
        grid    (map (partial str row-prefix)
                     (map str/join (partition row-length cells)))
        heading (str (msg/t :card/heading) " — " (:session/id session))]
    (str/join "\n" (remove nil? (concat [heading ""]
                                        grid
                                        [""]
                                        (progress-lines session))))))

(comment
  (board-report {:session/id "demo" :session/marked #{} :session/lines #{}
                 :session/turns 0 :session/card {:card/squares []}}))
