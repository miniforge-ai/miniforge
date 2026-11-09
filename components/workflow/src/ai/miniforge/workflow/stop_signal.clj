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
(ns ai.miniforge.workflow.stop-signal
  "Cooperative cancel checks. A cancel sets `:stopped` in the run's control
   state and interrupts no thread. Work checks `stopped?` at its own
   boundaries; a wait between steps uses `sleep!`, which ends on a stop.
   LLM calls see the cancel through `llm/call-with-cancel-signal`."
  (:require
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} poll-ms
  "How often `sleep!` re-reads the control state."
  100)

(defn ^{:stratum 0} stopped?
  "True when `control-state` (an atom, or nil) is stopped."
  [control-state]
  (boolean (and control-state (es/cancelled? control-state))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} sleep!
  "Sleep `ms` unless `control-state` is stopped first. The sleep runs in
   slices of `poll-ms`, so a stop before or during it ends it within one
   slice. Returns true when a stop ended or skipped the sleep, false when
   it ran to the end."
  [control-state ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (let [remaining (- deadline (System/currentTimeMillis))]
        (cond
          (stopped? control-state) true
          (pos? remaining) (do (Thread/sleep (long (min remaining poll-ms)))
                               (recur))
          :else false)))))

(comment ::stop-signal)
