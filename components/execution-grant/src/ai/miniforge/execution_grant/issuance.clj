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
(ns ai.miniforge.execution-grant.issuance
  "Eligibility gate for runtime-owned irreversible-effect issuance."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.eligibility :as eligibility]
   [ai.miniforge.execution-grant.issuance.policy :as policy]
   [ai.miniforge.execution-grant.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refused
  "Return a well-formed authority request as an unauthorized anomaly."
  [message-key request]
  (anomaly/sub-anomaly
   :unauthorized
   :anomalies.execution-grant/refused
   (msg/t message-key)
   {:workflow-run/id (:workflow-run/id request)
    :effect/id (:effect/id request)
    :effect/class (:effect/class request)}))

(defn- ^{:stratum 0} allow-preflight?
  [request]
  (= :allow (get-in request [:effect/preflight :preflight/result])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare
  "Return policy-derived issue options, or refuse before grant issuance."
  [breach-dir request now]
  (let [options (policy/grant-options request now)]
    (cond
      (not (allow-preflight? request))
      (refused :issuance/preflight-refused request)

      (not (eligibility/eligible? breach-dir
                                  (:principal options)
                                  (:effect-class options)
                                  (:constraints options)))
      (refused :issuance/ineligible request)

      :else options)))
