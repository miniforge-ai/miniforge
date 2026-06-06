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

(ns ai.miniforge.connector-retry.interface
  (:require [ai.miniforge.connector-retry.backoff :as backoff]
            [ai.miniforge.connector-retry.circuit-breaker :as cb]))

;; -- Error Classification --
(def error-categories
  "N2 §6.1 error categories"
  #{:transient :permanent :rate-limited})

(defn retryable?
  "Returns true if the error category allows retry."
  [error-category]
  (contains? #{:transient :rate-limited} error-category))

;; -- Backoff Strategies --
(defn compute-delay
  "Compute retry delay in ms for a given attempt using the retry policy.
   Policy keys: :retry/strategy (:fixed, :exponential, :jittered-exponential)
                :retry/initial-delay-ms
                :retry/max-delay-ms (optional)
                :retry/backoff-multiplier (for exponential, default 2.0)
                :retry/jitter-min (for jittered, default 0.5)
                :retry/jitter-max (for jittered, default 1.0)"
  [policy attempt]
  (backoff/compute-delay policy attempt))

(defn should-retry?
  "Returns true if retry should be attempted given policy and attempt number."
  [policy attempt error-category]
  (backoff/should-retry? policy attempt error-category))

(defn retry-sequence
  "Returns a lazy sequence of delay values for attempts 0..max-attempts-1."
  [policy]
  (backoff/retry-sequence policy))

;; -- Circuit Breaker --
(defn create-circuit-breaker
  "Create a circuit breaker state map.
   Options: :breaker/failure-threshold (default 5)
            :breaker/reset-timeout-ms (default 60000)"
  ([] (cb/create-circuit-breaker {}))
  ([opts] (cb/create-circuit-breaker opts)))

(defn record-success
  "Record a successful operation. Returns updated breaker state."
  [breaker]
  (cb/record-success breaker))

(defn record-failure
  "Record a failed operation. Returns updated breaker state."
  [breaker]
  (cb/record-failure breaker))

(defn allow-request?
  "Returns true if the circuit breaker allows a request through.
   Checks current time against reset timeout for half-open transition."
  ([breaker] (cb/allow-request? breaker (System/currentTimeMillis)))
  ([breaker now-ms] (cb/allow-request? breaker now-ms)))

(defn breaker-state
  "Returns current breaker state: :closed, :open, or :half-open"
  ([breaker] (cb/breaker-state breaker (System/currentTimeMillis)))
  ([breaker now-ms] (cb/breaker-state breaker now-ms)))
