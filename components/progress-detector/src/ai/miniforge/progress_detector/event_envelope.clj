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

(ns ai.miniforge.progress-detector.event-envelope
  "Normalizes raw tool-use events into validated Observation records.

   ## Responsibilities

   - Assigns a per-agent-run monotonic :seq starting at 0. Each
     normalizer instance owns its own counter so concurrent agent
     runs do not interleave sequence numbers.
   - Copies :tool/id and :timestamp from the source event.
   - Redacts :tool/input and :tool/output to a non-reversible token
     so file content, secrets, and command args never reach detector
     evidence:
       - nil          -> preserved as nil
       - any value    -> 'hash:<hex>:len<n>' token
   - Passes :tool/error? boolean through unchanged.
   - Optionally passes :tool/duration-ms through unchanged.
   - Attaches :resource/version-hash from the source event when the
     supplied ToolProfile declares :determinism
     :stable-with-resource-version and the event carries the field.
   - Validates the result against schema/Observation; throws ex-info
     on failure (caller bug).

   ## Usage

     (def normalize-run-a (make-normalizer))
     (normalize-run-a event)
     (normalize-run-a event tool-profile)

   `make-normalizer` is the one production entry point. Each agent
   run calls it once at session start and threads the returned
   function through its event stream."
  (:require
   [malli.core :as m]
   [ai.miniforge.progress-detector.messages :as msg]
   [ai.miniforge.progress-detector.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:private seq-start
  "Per-run sequence counters begin at this value; the first emitted
   :seq advances to start+1 = 0."
  -1)

;------------------------------------------------------------------------------ Layer 1
;; Redaction — always-hash, never preserve raw content

(defn- redact-value
  "Reduce v to a non-reversible token. Strings, structured data,
   numbers — everything non-nil becomes 'hash:<hex>:len<n>' so file
   content, command args, and tokens never leak into detector
   evidence. nil is preserved (absence is meaningful).

   The length suffix preserves enough diagnostic signal (output got
   shorter / longer) without exposing content."
  [v]
  (when (some? v)
    (let [printed (if (string? v) v (pr-str v))]
      (str "hash:" (Integer/toHexString (hash v)) ":len" (count printed)))))

;------------------------------------------------------------------------------ Layer 2
;; Normalization — per-run instance owns its own counter

(defn- build-observation
  "Compose the Observation map for `event` against `tool-profile`,
   stamping in the supplied `seq-num`. Pure — no I/O, no atoms."
  [event tool-profile seq-num]
  (let [attach-vh? (= :stable-with-resource-version
                      (:determinism tool-profile))]
    (cond-> {:tool/id   (:tool/id event)
             :seq       seq-num
             :timestamp (:timestamp event)}
      (contains? event :tool/input)
      (assoc :tool/input (redact-value (:tool/input event)))

      (contains? event :tool/output)
      (assoc :tool/output (redact-value (:tool/output event)))

      (contains? event :tool/error?)
      (assoc :tool/error? (:tool/error? event))

      (contains? event :tool/duration-ms)
      (assoc :tool/duration-ms (:tool/duration-ms event))

      (and attach-vh? (contains? event :resource/version-hash))
      (assoc :resource/version-hash (:resource/version-hash event)))))

(defn- validate-observation!
  "Throw ex-info if obs fails Observation schema validation, else
   return obs unchanged."
  [event obs]
  (if (m/validate schema/Observation obs)
    obs
    (throw (ex-info (msg/t :envelope/validation-failed)
                    {:type   ::validation-failed
                     :event  event
                     :obs    obs
                     :errors (m/explain schema/Observation obs)}))))

(defn make-normalizer
  "Return a per-run normalizer function. The returned fn closes over
   its own monotonic :seq counter starting at 0; concurrent agent
   runs do not interleave seq numbers because each holds its own
   counter atom.

   Returned fn arities:
     (normalize event)               - no tool profile
     (normalize event tool-profile)  - profile gates :resource/version-hash
                                       attachment

   Returns: validated Observation map.
   Throws:  ex-info with ::validation-failed when the result fails
            Observation validation — indicates a bug in the caller
            or an incompatible schema/event contract."
  []
  (let [counter (atom seq-start)]
    (fn normalize
      ([event]
       (normalize event nil))
      ([event tool-profile]
       (let [seq-num (swap! counter inc)
             obs     (build-observation event tool-profile seq-num)]
         (validate-observation! event obs))))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Per-run normalizer — :seq starts at 0
  (def normalize (make-normalizer))

  (normalize {:tool/id          :tool/Read
              :timestamp        (java.time.Instant/now)
              :tool/input       "src/foo.clj"
              :tool/output      "(ns foo)\n"
              :tool/error?      false
              :tool/duration-ms 42})
  ;; => {:tool/id :tool/Read :seq 0 :timestamp #inst "..."
  ;;     :tool/input "hash:abc123:len12" :tool/output "hash:def456:len9"
  ;;     :tool/error? false :tool/duration-ms 42}

  ;; Stable profile -> :resource/version-hash attached
  (normalize {:tool/id               :tool/Read
              :timestamp             (java.time.Instant/now)
              :resource/version-hash "sha256:abc123"}
             {:tool/id     :tool/Read
              :determinism :stable-with-resource-version})
  ;; => {:tool/id :tool/Read :seq 1 :timestamp #inst "..."
  ;;     :resource/version-hash "sha256:abc123"}

  ;; Concurrent runs each get their own counter from 0
  (def normalize-a (make-normalizer))
  (def normalize-b (make-normalizer))
  ;; (normalize-a event) and (normalize-b event) both yield :seq 0

  :leave-this-here)
