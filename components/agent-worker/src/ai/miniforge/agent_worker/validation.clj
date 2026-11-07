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
(ns ai.miniforge.agent-worker.validation
  "Boundary validation for the interface: an invalid input is an
   `:invalid-input` anomaly naming the schema it failed."
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-worker.interface.protocol :as protocol]
   [ai.miniforge.agent-worker.messages :as msg]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.tenancy.interface :as tenancy]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} worker? [x] (satisfies? protocol/AgentWorker x))

(defn- ^{:stratum 0} invalid
  [schema value]
  (anomaly/sub-anomaly :invalid-input
                       :anomalies.agent-worker/invalid-input
                       (msg/t :anomalies.agent-worker/invalid-input)
                       {:explain (m/explain schema value)}))

(def ^{:stratum 0} Acting tenancy/ActingContext)

(def ^{:stratum 0} Lease env/Lease)

(def ^{:stratum 0} Grant grant/ExecutionGrant)

(def ^{:stratum 0} MaybeGrant [:maybe grant/ExecutionGrant])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ControllerOptions
  [:map
   [:workers [:map-of :keyword [:fn worker?]]]
   [:environment map?]
   [:clock fn?]])

(defn- ^{:stratum 1} pair-failure
  [[schema value]]
  (when-not (m/validate schema value)
    (invalid schema value)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check
  "Nil when every `schema value` pair validates, else the first failure."
  [& pairs]
  (some pair-failure (partition 2 pairs)))
