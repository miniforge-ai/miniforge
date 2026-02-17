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
  "Key event lexer -- normalizes raw Lanterna key events into
   semantic key tokens for the Elm update loop.

   The lexer table is loaded from config/tui/key-tokens.edn, making
   the full input pipeline data-driven:

     raw Lanterna event -> key token (this ns, via key-tokens.edn)
                        -> action token (keybindings.edn)
                        -> handler fn (action registry)

   Character keys return {:key :key/j :char \\j} (mapped) or
   {:key nil :char \\x} (unmapped). Special keys return bare
   keywords like :key/enter."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.tui-engine.screen :as screen]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Lexer table (EDN-driven)

(def ^:private key-tokens
  "Lexer config loaded from EDN. Two sections:
   :char-keys    — string->keyword map (converted to char->keyword at load time)
   :special-keys — Lanterna event type keyword -> key token keyword"
  (-> (io/resource "config/tui/key-tokens.edn")
      slurp
      edn/read-string))

(def ^:private char-key-map
  "Map single characters to semantic key tokens.
   Built from :char-keys in key-tokens.edn.
   Single-char strings are converted to char keys for O(1) lookup."
  (into {}
    (map (fn [[s token]] [(first s) token]))
    (:char-keys key-tokens)))

(def ^:private special-key-map
  "Map Lanterna event type keywords to semantic key tokens.
   Loaded directly from :special-keys in key-tokens.edn."
  (:special-keys key-tokens))

;; ─────────────────────────────────────────────────────────────────────────────
;; Key normalization

(defn normalize-key
  "Convert a raw Lanterna key event map to a normalized message.

   Character keys return {:key :key/r :char \\r} (mapped) or {:key nil :char \\x}
   (unmapped). This allows mode-aware dispatching: normal mode uses :key for
   vi commands, while command/search mode uses :char for text input.

   Special keys return bare keywords: :key/enter, :key/escape, etc."
  [raw-event]
  (when raw-event
    (let [event-type (:type raw-event)]
      (if (= :character event-type)
        (let [ch (:char raw-event)
              semantic (get char-key-map ch)]
          {:key semantic :char ch})
        (get special-key-map event-type)))))

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
