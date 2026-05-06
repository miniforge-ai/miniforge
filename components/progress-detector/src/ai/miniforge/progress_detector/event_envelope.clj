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

   - Assigns a per-runtime monotonic :seq via an atom-backed counter.
   - Copies :tool/id and :timestamp from the source event.
   - Redacts :tool/input and :tool/output to bounded summaries:
       - Strings   -> truncated to max-summary-chars characters + ellipsis.
       - Structured -> replaced with a compact 'hash:<hex>' string.
       - nil        -> preserved as nil.
     Both summaries are guaranteed to fit within 256 bytes.
   - Passes :tool/error? boolean through unchanged.
   - Optionally passes :tool/duration-ms through unchanged.
   - Attaches :resource/version-hash from the source event when the
     supplied ToolProfile declares :determinism
     :stable-with-resource-version and the event carries the field.
   - Validates the result against schema/Observation; throws ex-info
     on failure (caller bug).

   ## Usage

     (normalize event)
     (normalize event tool-profile)

   ## Counter lifecycle

   seq-counter is a defonce atom. It survives namespace reloads but
   resets to 0 on JVM restart. Tests must call reset-counter! in a
   :each fixture to prevent cross-test seq coupling."
  (:require
   [malli.core :as m]
   [ai.miniforge.progress-detector.messages :as msg]
   [ai.miniforge.progress-detector.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Per-runtime sequence counter

;; Atom incremented atomically on every normalize call.
;; defonce ensures a single counter survives namespace reloads.
(defonce ^:private seq-counter (atom 0))

(def ^:private max-summary-chars
  "Strings longer than this are truncated with a trailing ellipsis.
   240 chars + one multi-byte ellipsis comfortably fits within 256 bytes."
  240)

;------------------------------------------------------------------------------ Layer 1
;; Redaction helpers

(defn- redact-string
  "Truncate s to max-summary-chars, appending the ellipsis character when
   truncation occurs."
  [s]
  (if (<= (count s) max-summary-chars)
    s
    (str (subs s 0 max-summary-chars) "…")))

(defn- structural-hash
  "Produce a compact hex string from the Clojure structural hash of v.
   Output is always < 20 bytes (e.g. 'hash:7f3a9b2c')."
  [v]
  (str "hash:" (Integer/toHexString (hash v))))

(defn- redact-value
  "Reduce v to a bounded summary guaranteed to fit within 256 bytes.
   Strings are truncated; structured values become a hash token; nil -> nil."
  [v]
  (cond
    (nil? v)    nil
    (string? v) (redact-string v)
    :else       (structural-hash v)))

;------------------------------------------------------------------------------ Layer 1
;; Counter access

(defn- next-seq!
  "Return the next monotonic sequence number and advance the counter atomically."
  []
  (swap! seq-counter inc))

(defn ^:test-only reset-counter!
  "Reset the per-runtime sequence counter to 0.

   Marked ^:test-only — intended exclusively for test fixtures.
   Calling this in production code breaks seq monotonicity and will
   cause downstream detectors relying on observation ordering to
   produce incorrect results."
  []
  (reset! seq-counter 0))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn normalize
  "Map a raw tool-use event to an Observation record.

   Arguments:
     event        - map representing one tool-use invocation.
                    Required keys:
                      :tool/id    qualified keyword (e.g. :tool/Read)
                      :timestamp  java.time.Instant
                    Optional keys carried through (redacted or passthrough):
                      :tool/input       -> redacted to bounded summary
                      :tool/output      -> redacted to bounded summary
                      :tool/error?      -> boolean, passed through unchanged
                      :tool/duration-ms -> int, passed through unchanged
                      :resource/version-hash -> conditionally attached (see below)

     tool-profile - (optional) ToolProfile map for the invoked tool.
                    When :determinism equals :stable-with-resource-version
                    AND event contains :resource/version-hash, that value
                    (including nil) is included in the returned Observation.
                    Pass nil or omit to skip hash attachment entirely.

   Returns: validated Observation map (satisfies schema/Observation).
   Throws:  ex-info with ::validation-failed when the result fails
            Observation validation — indicates a bug in the caller or
            an incompatible schema/event contract."
  ([event]
   (normalize event nil))
  ([event tool-profile]
   (let [seq-num    (next-seq!)
         attach-vh? (= :stable-with-resource-version
                       (:determinism tool-profile))
         obs (cond-> {:tool/id   (:tool/id event)
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
               (assoc :resource/version-hash (:resource/version-hash event)))]
     (if (m/validate schema/Observation obs)
       obs
       (throw (ex-info (msg/t :envelope/validation-failed)
                       {:type   ::validation-failed
                        :event  event
                        :obs    obs
                        :errors (m/explain schema/Observation obs)}))))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Reset counter for REPL experiments
  (reset-counter!)

  ;; Basic normalization
  (normalize {:tool/id          :tool/Read
              :timestamp        (java.time.Instant/now)
              :tool/input       "src/foo.clj"
              :tool/output      "(ns foo)\n"
              :tool/error?      false
              :tool/duration-ms 42})
  ;; => {:tool/id :tool/Read :seq 1 :timestamp #inst "..."
  ;;     :tool/input "src/foo.clj" :tool/output "(ns foo)\n"
  ;;     :tool/error? false :tool/duration-ms 42}

  ;; Stable profile -> :resource/version-hash attached
  (normalize {:tool/id               :tool/Read
              :timestamp             (java.time.Instant/now)
              :resource/version-hash "sha256:abc123"}
             {:tool/id     :tool/Read
              :determinism :stable-with-resource-version})
  ;; => {:tool/id :tool/Read :seq 2 :timestamp #inst "..."
  ;;     :resource/version-hash "sha256:abc123"}

  ;; Structured input -> hash token
  (normalize {:tool/id    :tool/Bash
              :timestamp  (java.time.Instant/now)
              :tool/input {:command "git" :args ["status"]}})
  ;; => {:tool/id :tool/Bash :seq 3 :timestamp #inst "..."
  ;;     :tool/input "hash:7f3a9b2c"}

  :leave-this-here)
