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

(ns ai.miniforge.agent.interface.watchdog
  "Public boundary for the per-phase stream-gap watchdog.

   Re-exports the five primary operations and the config helper from
   ai.miniforge.agent.stream-watchdog.

   Usage:
     (require '[ai.miniforge.agent.interface.watchdog :as watchdog])

     (let [wd (watchdog/create-watchdog
                {:threshold-ms (watchdog/resolve-gap-threshold config :claude-code)
                 :phase-id     :implement
                 :backend      :claude-code
                 :event-stream event-stream
                 :workflow-id  workflow-id
                 :kill-fn      kill-subprocess!})]
       ;; on every agent event:
       (watchdog/ping! wd)
       ;; on normal completion:
       (watchdog/stop! wd)
       ;; to check for stall:
       (watchdog/stalled? wd))"
  (:require
   [ai.miniforge.agent.stream-watchdog :as watchdog]))

;; ---------------------------------------------------------------------------
;; Config helpers

(def resolve-gap-threshold
  "Resolve the stream-gap threshold (ms) for a backend.
   See `ai.miniforge.agent.stream-watchdog/resolve-gap-threshold`."
  watchdog/resolve-gap-threshold)

(def default-gap-threshold-ms
  "Default stream-gap threshold in ms (90 000)."
  watchdog/default-gap-threshold-ms)

(def default-check-interval-ms
  "Default watchdog check interval in ms (5 000)."
  watchdog/default-check-interval-ms)

;; ---------------------------------------------------------------------------
;; Watchdog lifecycle

(def create-watchdog
  "Create and start a stream-gap watchdog.
   See `ai.miniforge.agent.stream-watchdog/create-watchdog`."
  watchdog/create-watchdog)

(def ping!
  "Record a new agent event, resetting the gap timer.
   Named ping! (not reset!) to avoid shadowing clojure.core/reset!.
   See `ai.miniforge.agent.stream-watchdog/ping!`."
  watchdog/ping!)

(def stop!
  "Gracefully shut down the watchdog scheduler.
   See `ai.miniforge.agent.stream-watchdog/stop!`."
  watchdog/stop!)

(def stalled?
  "Return true if the watchdog fired and killed the agent subprocess.
   See `ai.miniforge.agent.stream-watchdog/stalled?`."
  watchdog/stalled?)
