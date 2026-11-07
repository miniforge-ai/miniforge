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
(ns ai.miniforge.agent-worker.refusal
  "Worker refusal and failure reasons as anomalies, each with its anomaly
   type and system message."
  (:require
   [ai.miniforge.agent-worker.messages :as msg]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} reasons-by-type
  "Anomaly type of each reason; unlisted reasons are faults."
  {:unauthorized #{:anomalies.agent-worker/forged-tenant :anomalies.agent-worker/forged-principal
                   :anomalies.agent-worker/grant-absent
                   :anomalies.agent-worker/grant-inactive :anomalies.agent-worker/grant-scope-mismatch
                   :anomalies.agent-worker/grant-exceeded :anomalies.agent-worker/grant-wrong-effect
                   :anomalies.agent-worker/grant-wrong-principal :anomalies.agent-worker/grant-not-bound
                   :anomalies.agent-worker/grant-denied :anomalies.agent-worker/budget-unbounded
                   :anomalies.agent-worker/budget-unenforceable}
   :conflict #{:anomalies.agent-worker/binding-mismatch :anomalies.agent-worker/stale-lease
               :anomalies.agent-worker/config-digest-mismatch :anomalies.agent-worker/conflicting-submission
               :anomalies.agent-worker/unknown-session :anomalies.agent-worker/turn-in-progress
               :anomalies.agent-worker/native-session-unknown}
   :unavailable #{:anomalies.agent-worker/worker-unavailable :anomalies.agent-worker/worker-preflight-failed}
   :unsupported #{:anomalies.agent-worker/steering-unsupported}})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} reason->type
  (into {} (for [[type reasons] reasons-by-type, reason reasons] [reason type])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} reason-anomaly
  [reason data]
  (anomaly/sub-anomaly (get reason->type reason :fault) reason (msg/t reason) data))
