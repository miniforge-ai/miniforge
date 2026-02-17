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
   for testing. LanternaScreen is loaded dynamically via create-screen to
   avoid Java class loading in Babashka/GraalVM contexts.")

;; ─────────────────────────────────────────────────────────────────────────────
;; Screen protocol

(defprotocol IScreen
  "Abstraction over terminal screen for rendering and input.
   Implementations: LanternaScreen (real terminal), MockScreen (testing)."
  (start-screen! [this] "Enter alternate screen mode.")
  (stop-screen! [this] "Exit alternate screen mode, restore terminal.")
  (get-size [this] "Return [cols rows].")
  (put-string! [this col row text fg bg bold?] "Write styled string at position.")
  (clear! [this] "Clear the screen buffer.")
  (refresh! [this] "Flush buffer to terminal (delta rendering).")
  (poll-input [this] "Non-blocking read. Returns key map or nil."))

;; ─────────────────────────────────────────────────────────────────────────────
;; Mock screen for testing

(defrecord MockScreen [state]
  ;; state is an atom: {:started? bool, :cells {[col row] {:char :fg :bg :bold?}}, :size [c r]}
  IScreen
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
  (or (:put-count @(:state mock-screen)) 0))

(defn mock-reset-put-count!
  "Reset the put-string! call counter to zero."
  [mock-screen]
  (swap! (:state mock-screen) assoc :put-count 0))

;; ─────────────────────────────────────────────────────────────────────────────
;; Factory (dynamically loads Lanterna)

(defn create-screen
  "Create a Lanterna terminal screen.
   Dynamically loads the Lanterna implementation to avoid Java class loading
   at namespace load time (required for Babashka/GraalVM compatibility)."
  [& [opts]]
  (let [create-fn (requiring-resolve 'ai.miniforge.tui-engine.screen.lanterna/create-lanterna-screen)]
    (create-fn opts)))
