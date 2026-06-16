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

(ns ai.miniforge.tui-engine.screen
  "Screen protocol and mock implementation for TUI rendering.

   The protocol defines the terminal abstraction. MockScreen is pure Clojure
   for testing. LanternaScreen provides the real terminal implementation."
  (:require
   [ai.miniforge.tui-engine.screen.lanterna :as lanterna]
   [ai.miniforge.tui-engine.screen.protocol :as protocol]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Screen protocol

(def IScreen
  "Protocol contract for terminal screen implementations."
  protocol/IScreen)

(def start-screen! "Enter alternate screen mode." protocol/start-screen!)
(def stop-screen! "Exit alternate screen mode, restore terminal." protocol/stop-screen!)
(def get-size "Return [cols rows]." protocol/get-size)
(def put-string! "Write styled string at position." protocol/put-string!)
(def clear! "Clear the screen buffer." protocol/clear!)
(def refresh! "Flush buffer to terminal." protocol/refresh!)
(def poll-input "Non-blocking read. Returns key map or nil." protocol/poll-input)

;; ─────────────────────────────────────────────────────────────────────────────
;; Mock screen for testing

(defrecord MockScreen [state]
  ;; state is an atom: {:started? bool, :cells {[col row] {:char :fg :bg :bold?}}, :size [c r]}
  protocol/IScreen
  (start-screen! [_]
    (swap! state assoc :started? true))

  (stop-screen! [_]
    (swap! state assoc :started? false))

  (get-size [_]
    (:size @state))

  (put-string! [_ col row text fg bg bold?]
    (swap! state update :put-count (fnil inc 0))
    (doseq [i (range (count text))]
      (swap! state assoc-in [:cells [(+ col i) row]]
             {:char (.charAt text i) :fg fg :bg bg :bold? bold?})))

  (clear! [_]
    (swap! state assoc :cells {}))

  (refresh! [_]
    (swap! state update :refresh-count (fnil inc 0)))

  (poll-input [_]
    (let [s @state]
      (when-let [input (first (:input-queue s))]
        (swap! state update :input-queue rest)
        input))))

(defn create-mock-screen
  "Create a mock screen for testing. Takes [cols rows] size."
  [[cols rows]]
  (->MockScreen (atom {:started? false
                        :cells {}
                        :size [cols rows]
                        :refresh-count 0
                        :input-queue []})))

(defn mock-enqueue-input!
  "Enqueue input events for a mock screen."
  [mock-screen events]
  (swap! (:state mock-screen) update :input-queue concat events))

(defn mock-get-cells
  "Get cell map from mock screen."
  [mock-screen]
  (:cells @(:state mock-screen)))

(defn mock-read-line
  "Read a line of text from mock screen at row. Returns string."
  [mock-screen row cols]
  (let [cells (mock-get-cells mock-screen)]
    (apply str (for [c (range cols)]
                 (if-let [cell (get cells [c row])]
                   (:char cell)
                   \space)))))

(defn mock-get-put-count
  "Get the number of put-string! calls on a mock screen."
  [mock-screen]
  (get @(:state mock-screen) :put-count 0))

(defn mock-reset-put-count!
  "Reset the put-string! call counter to zero."
  [mock-screen]
  (swap! (:state mock-screen) assoc :put-count 0))

;; ─────────────────────────────────────────────────────────────────────────────
;; Factory

(defn create-screen
  "Create a Lanterna terminal screen."
  [& [opts]]
  (lanterna/create-lanterna-screen opts))
