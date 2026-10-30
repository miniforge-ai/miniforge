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
(ns ai.miniforge.phase-deployment.deploy-governed
  "Application flow for one granted, transacted Kubernetes deployment."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.phase-deployment.deploy-authority :as authority]
   [ai.miniforge.phase-deployment.deploy-outcome :as outcome]
   [ai.miniforge.phase-deployment.deploy-transaction :as transaction]
   [ai.miniforge.phase-deployment.messages :as msg]
   [ai.miniforge.schema.interface :as schema]
   [clojure.string :as str])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fail
  [state stage message]
  (assoc state :flow/failure {:stage stage :message message}))

(defn- ^{:stratum 0} failure-detail
  [result fallback-key]
  (str (or (not-empty (:stderr result))
           (:error result)
           (:anomaly/message result)
           (msg/t fallback-key))))

(defn- ^{:stratum 0} advance
  [state step]
  (if (:flow/failure state) state (step state)))

(def ^{:stratum 0} unclaimed-states
  "Transaction states in which the deploy cannot have reached the cluster."
  #{:proposed :failed})

(defn- ^{:stratum 0} stopped?
  "True when the run's control state says the operator cancelled it."
  [context]
  (let [control (or (:control-state context)
                    (get-in context [:execution/opts :control-state]))]
    (boolean (and control (:stopped @control)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resolve-target
  [state]
  (let [result ((get-in state [:operations :target!]) (:deploy-config state))]
    (if (schema/failed? result)
      (fail state :preflight (failure-detail result :deploy/context-unavailable))
      (assoc state :target (:target result)))))

(defn- ^{:stratum 1} render-manifests
  [state]
  (let [result ((get-in state [:operations :render!]) (:target state))
        rendered (:rendered-yaml result)]
    (if (or (schema/failed? result) (str/blank? rendered))
      (fail state :preflight (failure-detail result :deploy/render-failed))
      (assoc state :rendered-yaml rendered))))

(defn- ^{:stratum 1} check-manifest-policy
  "Refuse rendered manifests the deployment pack blocks before they leave this
   process. A server dry run already sends the body to the cluster API, where
   admission webhooks and request-body audit logs can see it."
  [state]
  (let [{:keys [blocking require-approval]} (authority/manifest-findings (:rendered-yaml state))
        refused (concat blocking require-approval)]
    (if (seq refused)
      (fail state :authority (str/join "; " (map :message refused)))
      state)))

(defn- ^{:stratum 1} server-dry-run
  [state]
  (let [result ((get-in state [:operations :server-dry-run!])
                (:target state) (:rendered-yaml state))
        output (:stdout result)]
    (if (or (schema/failed? result) (str/blank? output))
      (fail state :preflight (failure-detail result :deploy/dry-run-failed))
      (assoc state :server-dry-run output))))

(defn- ^{:stratum 1} capture-rollback
  [state]
  (let [result ((get-in state [:operations :rollback-info!]) (:target state))]
    (if (schema/failed? result)
      (fail state :capture
            (failure-detail result :deploy/rollback-capture-failed))
      (let [rollback-info (:rollback-info result)]
        (-> state
            (assoc :rollback-info rollback-info)
            (assoc-in [:authority :effect/proposal :deploy/rollback-info]
                      rollback-info))))))

(defn- ^{:stratum 1} prepare-authority
  [state]
  (let [preflight {:rendered-yaml (:rendered-yaml state)
                   :server-dry-run (:server-dry-run state)
                   :app-label (get-in state [:target :app-label])}
        prepared (authority/prepare (:context state) (random-uuid)
                                    (:target state) preflight (:now state))]
    (if (anomaly/anomaly? prepared)
      (fail state :authority (:anomaly/message prepared))
      (assoc state :authority prepared))))

(defn- ^{:stratum 1} propose-effect
  [state]
  (let [proposed (transaction/propose! (:context state) (:authority state)
                                       (:now state))]
    (if (anomaly/anomaly? proposed)
      (fail state :proposal (:anomaly/message proposed))
      (assoc state :transaction proposed))))

(defn- ^{:stratum 1} require-permission
  [state]
  (if (authority/permitted? (:authority state))
    state
    (fail state :authority (msg/t :deploy/authority-denied))))

(defn- ^{:stratum 1} check-stop
  [state]
  (if (stopped? (:context state))
    (fail state :authority (msg/t :deploy/stopped))
    state))

(defn- ^{:stratum 1} commit-effect
  [state]
  (let [context (:context state)
        committed (transaction/commit!
                   context (:transaction state) (authority/breach-dir context)
                   (get-in state [:authority :authority/grant :grant/id])
                   (:operations state))]
    (if (anomaly/anomaly? committed)
      (fail state :apply (:anomaly/message committed))
      (assoc state :transaction committed))))

(defn- ^{:stratum 1} reconcile-effect
  [state]
  (let [reconciled (transaction/reconcile!
                    (:context state) (:transaction state)
                    (:operations state) (:now state))]
    (if (anomaly/anomaly? reconciled)
      (fail state :observe (:anomaly/message reconciled))
      (assoc state :transaction reconciled))))

(defn- ^{:stratum 1} revoke-abandoned
  "Revoke the grant of a deploy that ended before it could reach the
   cluster, so the grant cannot authorize a later attempt."
  [state]
  (let [grant-id (get-in state [:authority :authority/grant :grant/id])
        settled (get-in state [:transaction :effect/state])]
    (if (and grant-id (or (nil? settled) (contains? unclaimed-states settled)))
      (let [reason (if (stopped? (:context state))
                     :revocation/operator
                     :revocation/superseded)
            revoked (grant/revoke-stored! (authority/breach-dir (:context state))
                                          grant-id reason (:now state))]
        (cond-> state
          (anomaly/anomaly? revoked) (assoc :grant/revocation-failure revoked)))
      state)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} transact!
  "Resolve, render, check manifest policy, preflight, authorize, persist,
   commit, and reconcile one deploy. A deploy that ends before it could
   reach the cluster revokes its grant."
  [context deploy-config operations ^Instant now]
  (-> {:context context
       :deploy-config deploy-config
       :operations operations
       :now now}
      (advance resolve-target)
      (advance render-manifests)
      (advance check-manifest-policy)
      (advance server-dry-run)
      (advance prepare-authority)
      (advance require-permission)
      (advance capture-rollback)
      (advance propose-effect)
      (advance check-stop)
      (advance commit-effect)
      (advance reconcile-effect)
      revoke-abandoned
      outcome/from-state))
