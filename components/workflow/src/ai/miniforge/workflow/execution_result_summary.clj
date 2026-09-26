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
(ns ai.miniforge.workflow.execution-result-summary
  "Keys-only summaries of a phase result, for event payloads that must
   describe a result without carrying its contents: the shape of its
   `:output`, and a trimmed view of a failure's `:error`.

   Split out of `execution` (rule 210). `execution-dag` uses both to
   explain why the DAG executor was skipped.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} classify-output
  "Describe the shape of the :output value without leaking its contents.
   Returns a keyword suitable for event payload."
  [output]
  (cond
    (nil? output) :nil
    (map? output) :map
    (sequential? output) :sequential
    (string? output) :string
    :else :other))

(def ^{:stratum 0} ^:private failure-statuses
  #{:error :failed :failure})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} failed?
  "True when a result map carries a failure status."
  [result]
  (contains? failure-statuses (:status result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} summarize-error
  "Keys-only/trimmed summary of a failure result's :error map so the event
   carries enough to diagnose without leaking large payloads (stack traces,
   token arrays). Returns nil when the result isn't a failure or carries no
   diagnosable error fields.

   Reads the canonical response-shape error field (:message) and emits the
   canonical event-schema field (:error/message). Any producer using a
   non-canonical shape MUST convert at its boundary — this fn is inside
   the workflow runtime and does not coerce."
  [result]
  (when (failed? result)
    (let [err (:error result)
          msg (:message err)
          data-keys (some-> err :data keys sort)
          summary (cond-> {}
                    msg                       (assoc :error/message
                                                     (subs (str msg) 0 (min 500 (count (str msg)))))
                    (keyword? (:anomaly err)) (assoc :anomaly (:anomaly err))
                    (seq data-keys)           (assoc :error/data-keys (vec data-keys)))]
      (not-empty summary))))
