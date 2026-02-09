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

(ns ai.miniforge.tui-engine.input
  "Key event parsing -- normalizes raw Lanterna key events into
   semantic message keywords for the Elm update loop.

   Raw Lanterna events are maps like {:type :character :char \\j}.
   This module translates them into normalized TUI messages like :key/j, :key/enter, etc."
  (:require
   [ai.miniforge.tui-engine.screen :as screen]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Key normalization

(def ^:private char-key-map
  "Map single characters to semantic key names."
  {\j     :key/j
   \k     :key/k
   \h     :key/h
   \l     :key/l
   \g     :key/g
   \G     :key/G
   \q     :key/q
   \1     :key/d1
   \2     :key/d2
   \3     :key/d3
   \4     :key/d4
   \5     :key/d5
   \/     :key/slash
   \:     :key/colon
   \space :key/space
   \tab   :key/tab
   \?     :key/question})

(defn normalize-key
  "Convert a raw Lanterna key event map to a normalized keyword message.

   Returns a keyword like :key/j, :key/enter, :key/escape, or
   {:type :char :char c} for unmapped characters."
  [raw-event]
  (when raw-event
    (case (:type raw-event)
      :character
      (let [ch (:char raw-event)]
        (or (get char-key-map ch)
            {:type :char :char ch}))

      :enter     :key/enter
      :escape    :key/escape
      :backspace :key/backspace
      :arrow-up  :key/up
      :arrow-down :key/down
      :arrow-left :key/left
      :arrow-right :key/right
      :tab       :key/tab
      :eof       :key/eof

      ;; Unknown key type
      nil)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Input polling

(defn poll-key
  "Poll the screen for a key event and normalize it.
   Returns a normalized key message or nil if no input."
  [screen]
  (normalize-key (screen/poll-input screen)))

(defn drain-keys
  "Drain all pending key events from the screen. Returns vector of normalized keys."
  [screen]
  (loop [keys []]
    (if-let [k (poll-key screen)]
      (recur (conj keys k))
      keys)))
