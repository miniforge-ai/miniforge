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
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.pr-lifecycle.messages :as messages])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

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

(defn- ^{:stratum 0} ready?
  [readiness]
  (and (true? (:ready? readiness)) (empty? (:blocking readiness))))

(defn- ^{:stratum 0} readiness-blocker
  [reason]
  {:rule-id (keyword "merge-readiness" (name reason))
   :message (messages/t :merge/readiness-blocked {:reason (name reason)})})

(defn ^{:stratum 0} breach-dir
  "The registry and breach-history directory for merge grants."
  [context]
  (or (:grant-breach-dir context)
      (str (config/miniforge-home) "/grant-breaches")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} readiness-classification
  "Classify each merge-readiness blocking reason as a denying violation."
  [readiness]
  (cond-> (assoc empty-classification
                 :blocking (mapv readiness-blocker (:blocking readiness)))
    (and (not (ready? readiness)) (empty? (:blocking readiness)))
    (update :blocking conj (readiness-blocker :readiness-unknown))))

(defn ^{:stratum 1} request
  "Build the closed request for one merge of `target`, a map of
   `:pr/repo`, `:pr/number` and `:pr/head-sha`. The preflight result is
   the readiness the caller evaluated, so issuance refuses an unready PR."
  [context effect-id target readiness]
  {:workflow-run/id (:run-id context)
   :workflow-run/status :running
   :effect/id effect-id
   :effect/class :effect/merge
   :effect/preflight {:preflight/type :preflight/merge-readiness
                      :preflight/result (if (ready? readiness) :allow :deny)}
   :pr/repo (:pr/repo target)
   :pr/number (:pr/number target)
   :pr/head-sha (:pr/head-sha target)})

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

(defn- ^{:stratum 1} issue-registered
  "Issue a grant and register it, so commit can re-read and revoke it.
   Registration can fail after the record became readable, so a failure
   revokes the issued id and reports both."
  [context request now]
  (let [dir (breach-dir context)
        issued (grant/issue-for-effect dir request now)
        registered (if (anomaly/anomaly? issued) issued (grant/register! dir issued))]
    (if (and (anomaly/anomaly? registered) (not (anomaly/anomaly? issued)))
      (update registered :anomaly/data assoc
              :grant/id (:grant/id issued)
              :grant/revocation (grant/revoke-stored! dir (:grant/id issued)
                                                      :revocation/superseded now))
      registered)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare
  "Issue, register, authorize, and decide authority for one merge of
   `target` at the readiness the caller evaluated."
  [context effect-id target readiness merge-method ^Instant now]
  (let [request (request context effect-id target readiness)
        grant-record (issue-registered context request now)]
    (if (anomaly/anomaly? grant-record)
      grant-record
      (let [scope (effect-scope request merge-method)
            authorization (grant/authorize grant-record
                                           {:effect/scope scope
                                            :usage/count 1}
                                           now)
            envelope (fx/sign-envelope
                      (gate/decide (readiness-classification readiness)
                                    unpinned-policy
                                    authorization))]
        (authority-record request merge-method grant-record
                          authorization envelope)))))
