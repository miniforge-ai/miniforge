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

(ns ai.miniforge.clock.interface
  "Tiny timing helpers shared across the OSS components.

   Replaces the duplicated

       (let [start (System/currentTimeMillis)]
         …work…
         (- (System/currentTimeMillis) start))

   pattern that grew up across loop/gates, connector-linter,
   compliance-scanner, and similar places that report a
   `:duration-ms` alongside the result of a single block.")

;------------------------------------------------------------------------------ Layer 0
;; No in-namespace dependencies.

(defn now-ms
  "Wall-clock millis. Wrapped so tests can `with-redefs` the clock."
  []
  (System/currentTimeMillis))

(defn elapsed-since
  "Milliseconds since `start-ms`. Pair with `now-ms` at entry — call
   `now-ms` BEFORE the work, then `elapsed-since` AFTER:

       (let [start (clock/now-ms)]
         …work…
         {:duration-ms (clock/elapsed-since start)})

   Note: `(elapsed-since (now-ms))` would measure ~0 because the start
   timestamp is created at the end of the work. The two-step pattern
   above is the only correct usage.

   Wall-clock based, so an NTP step or VM clock adjustment could in
   principle make `(now-ms)` go backwards. Clamp the result to
   non-negative so downstream `:duration-ms` consumers (which model the
   field as non-negative; see `components/loop/schema.clj`,
   `components/schema/core.clj`, `components/agent/reviewer.clj`)
   never see a negative duration."
  [start-ms]
  (max 0 (- (now-ms) start-ms)))

;------------------------------------------------------------------------------ Layer 1
;; Composes Layer 0.

(defmacro with-duration
  "Evaluate `body`, return `{:result <body-value> :duration-ms <ms>}`.

   Use when the call site wants both the result and the elapsed time
   in a single envelope. For branching code where each branch builds a
   bespoke result map and attaches its own `:duration-ms`, prefer the
   simpler `(elapsed-since (now-ms))` form."
  [& body]
  `(let [start# (now-ms)
         result# (do ~@body)]
     {:result      result#
      :duration-ms (elapsed-since start#)}))
