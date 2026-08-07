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
(ns ai.miniforge.pr-lifecycle.merge-authority
  "Runtime authority preparation for one exact pull-request merge."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.gate.interface :as gate])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-breach-dir-property
  ".miniforge/grant-breaches")

(def ^{:stratum 0} empty-classification
  {:blocking []
   :require-approval []
   :warnings []
   :audits []
   :unknown []})

(def ^{:stratum 0} unpinned-policy
  {:pins/pack-revision nil
   :pins/rule-ids []
   :pins/event-watermark nil})

(def ^{:stratum 0} allow-decisions
  #{:allow :allow-with-obligations})

(defn- ^{:stratum 0} effect-scope
  [request merge-method]
  (-> (dissoc request :workflow-run/status :effect/class :effect/preflight)
      (assoc :merge/method merge-method)))

(defn ^{:stratum 0} request
  "Build the closed, runtime-attested request for one merge effect."
  [context effect-id repo pr-number]
  {:workflow-run/id (:run-id context)
   :workflow-run/status :running
   :effect/id effect-id
   :effect/class :effect/merge
   :effect/preflight {:preflight/type :preflight/merge-readiness
                      :preflight/result :allow}
   :pr/repo repo
   :pr/number pr-number})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} breach-dir
  [context]
  (or (:grant-breach-dir context)
      (str (System/getProperty "user.home") "/" default-breach-dir-property)))

(defn ^{:stratum 1} permitted?
  "True only when both the grant check and DecisionEnvelope allow the effect."
  [{:authority/keys [authorization envelope]}]
  (and (grant/authorized? authorization)
       (contains? allow-decisions (:envelope/decision envelope))))

(defn- ^{:stratum 1} authority-record
  [request merge-method grant-record authorization envelope]
  {:effect/id (:effect/id request)
   :effect/proposal (effect-scope request merge-method)
   :authority/grant grant-record
   :authority/authorization authorization
   :authority/envelope envelope})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare
  "Issue, authorize, and decide authority for a preflight-approved merge."
  [context effect-id repo pr-number merge-method ^Instant now]
  (let [request (request context effect-id repo pr-number)
        grant-record (grant/issue-for-effect (breach-dir context) request now)]
    (if (anomaly/anomaly? grant-record)
      grant-record
      (let [scope (effect-scope request merge-method)
            authorization (grant/authorize grant-record
                                           {:effect/scope scope
                                            :usage/count 1}
                                           now)
            envelope (gate/decide empty-classification
                                  unpinned-policy
                                  authorization)]
        (authority-record request merge-method grant-record
                          authorization envelope)))))
