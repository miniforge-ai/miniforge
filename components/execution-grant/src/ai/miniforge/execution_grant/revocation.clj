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
(ns ai.miniforge.execution-grant.revocation
  "For-cause grant revocation backed by durable breach evidence."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.breach :as breach]
   [ai.miniforge.execution-grant.core :as core])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} breach-record
  [grant {:keys [axis limit observed detection]} ^Instant now]
  {:breach/id (random-uuid)
   :breach/principal (:grant/principal grant)
   :breach/grant-id (:grant/id grant)
   :breach/effect-class (:grant/effect-class grant)
   :breach/axis axis
   :breach/limit limit
   :breach/observed observed
   :breach/detection detection
   :breach/at now})

(defn- ^{:stratum 0} revocation-reason
  [axis]
  (case axis
    :constraint/max-cost-usd :breach/cost-exceeded
    :constraint/max-tokens :breach/token-exceeded
    :constraint/max-count :breach/count-exceeded
    :breach/scope-violation))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} revoke-for-cause!
  "Persist the breach, then revoke its grant with the matching cause.

   A persistence failure returns its anomaly unchanged and leaves the
   grant live; callers never receive an apparently complete revocation
   with its governing evidence missing."
  [dir grant observation ^Instant now]
  (let [record (breach-record grant observation now)
        reason (revocation-reason (:axis observation))
        recorded (breach/record! dir record)]
    (if (anomaly/anomaly? recorded)
      recorded
      (core/revoke grant reason now))))
