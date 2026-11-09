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
(ns ai.miniforge.agent-environment.refusal
  "Refusal and failure reasons as anomalies: each reason's anomaly type and
   its system message, and which provider anomalies count as refusals."
  (:require
   [ai.miniforge.agent-environment.messages :as msg]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} reasons-by-type
  "Anomaly type of each refusal or failure reason; unlisted reasons are faults."
  {:unauthorized #{:anomalies.agent-environment/forged-tenant :anomalies.agent-environment/forged-principal
                   :anomalies.agent-environment/grant-absent :anomalies.agent-environment/grant-inactive
                   :anomalies.agent-environment/grant-scope-mismatch :anomalies.agent-environment/grant-exceeded
                   :anomalies.agent-environment/grant-wrong-effect :anomalies.agent-environment/grant-wrong-principal
                   :anomalies.agent-environment/grant-denied}
   :invalid-input #{:anomalies.agent-environment/request-expired :anomalies.agent-environment/invalid-input}
   :unsupported #{:anomalies.agent-environment/preflight-failed :anomalies.agent-environment/isolation-unavailable
                 :anomalies.agent-environment/limits-exceeded}
   :conflict #{:anomalies.agent-environment/conflicting-request :anomalies.agent-environment/acquire-in-flight
               :anomalies.agent-environment/environment-cancelled :anomalies.agent-environment/environment-released
               :anomalies.agent-environment/environment-expired :anomalies.agent-environment/cleanup-uncertain
               :anomalies.agent-environment/unknown-lease :anomalies.agent-environment/stale-lease
               :anomalies.agent-environment/uid-mismatch :anomalies.agent-environment/operation-not-admitted}})

(def ^{:stratum 0} refusal-types
  "Anomaly types a provider result is receipted as refused, not failed."
  #{:conflict :unauthorized})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} reason->type
  (into {} (for [[type reasons] reasons-by-type, reason reasons] [reason type])))

(defn ^{:stratum 1} result-outcome
  [result]
  (if (refusal-types (:anomaly/type result)) :outcome/refused :outcome/failed))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} reason-anomaly
  [reason data]
  (anomaly/sub-anomaly (get reason->type reason :fault) reason (msg/t reason) data))
