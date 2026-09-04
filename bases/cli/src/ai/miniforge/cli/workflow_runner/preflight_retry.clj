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
(ns ai.miniforge.cli.workflow-runner.preflight-retry
  "Bounded retry around the backend health probe. One 30s probe timeout
   landing while the CLI is still releasing a previous session used to
   refuse the whole workflow; this namespace runs the probe up to the
   configured attempt count (`preflight-support/backend-preflight-attempts`),
   pauses between failed attempts, and logs each failure as
   `:llm/preflight-retry` so a refusal is attributable to N timeouts
   rather than one. Split out of `ai.miniforge.cli.workflow-runner.preflight`
   (rule 210: the retry loop pushed that namespace to a fourth layer)."
  (:require
   [ai.miniforge.logging.interface :as logging]
   [ai.miniforge.cli.workflow-runner.preflight-probe :as probe]
   [ai.miniforge.cli.workflow-runner.preflight-support :as support]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private nanos-per-ms
  "Nanoseconds in one millisecond; converts `System/nanoTime` deltas to
   the millisecond unit the retry log reports."
  1000000)

(defn ^{:stratum 0} stderr-logger
  "Fallback logger for a runtime context that carries no `:logger`:
   warn-and-above to stderr, so retry diagnostics survive `quiet` mode
   without landing on stdout, which quiet callers may parse."
  []
  (logging/create-logger {:min-level :warn
                          :config {:observability {:log-sinks [:stderr]}}}))

(defn- ^{:stratum 0} log-attempt-failed!
  "Record one failed health-probe attempt. `will-retry?` is false on the
   final attempt, whose failure is what the anomaly then carries."
  [logger {:keys [backend cmd-path]} attempt attempts elapsed-ms probe-response]
  (logging/warn logger :llm :llm/preflight-retry
                {:data {:backend backend
                        :cmd-path cmd-path
                        :attempt attempt
                        :attempts attempts
                        :elapsed-ms elapsed-ms
                        :will-retry? (< attempt attempts)
                        :error-type (get-in probe-response [:error :data :type])
                        :error-message (get-in probe-response [:error :message])}}))

(defn- ^{:stratum 0} pause-before-retry!
  [pause-ms]
  (when (pos? pause-ms)
    (Thread/sleep ^long pause-ms)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} elapsed-ms-since
  "Milliseconds since a `System/nanoTime` reading. Monotonic, so a wall
   clock adjustment mid-probe cannot log a negative duration."
  [started-nanos]
  (quot (- (System/nanoTime) started-nanos) nanos-per-ms))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} probe-backend-with-retries
  "Run the health probe up to the configured attempt count, pausing
   between failed attempts. Returns the first successful probe response,
   or the last failure when every attempt failed."
  [logger llm-client stamp workdir]
  (let [attempts (support/backend-preflight-attempts)
        pause-ms (support/backend-preflight-retry-pause-ms)]
    (loop [attempt 1]
      (let [started-nanos (System/nanoTime)
            probe-response (probe/run-backend-probe llm-client stamp workdir)
            elapsed-ms (elapsed-ms-since started-nanos)]
        (if (support/response-succeeded? probe-response)
          probe-response
          (do
            (log-attempt-failed! logger stamp attempt attempts elapsed-ms probe-response)
            (if (< attempt attempts)
              (do
                (pause-before-retry! pause-ms)
                (recur (inc attempt)))
              probe-response)))))))
