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
   [ai.miniforge.phase-deployment.deploy-authority :as authority]
   [ai.miniforge.phase-deployment.deploy-outcome :as outcome]
   [ai.miniforge.phase-deployment.deploy-transaction :as transaction]
   [ai.miniforge.phase-deployment.messages :as msg]
   [ai.miniforge.schema.interface :as schema]
   [clojure.string :as str])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failed-flow?
  [state]
  (contains? state :flow/failure))

(defn- ^{:stratum 0} fail
  [state stage message]
  (assoc state :flow/failure {:stage stage :message message}))

(defn- ^{:stratum 0} failure-detail
  [result fallback-key]
  (str (or (not-empty (:stderr result))
           (:error result)
           (:anomaly/message result)
           (msg/t fallback-key))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} advance
  [state step]
  (if (failed-flow? state) state (step state)))

(defn- ^{:stratum 1} resolve-target
  [state]
  (let [result ((get-in state [:operations :target!]) (:deploy-config state))]
    (if (schema/failed? result)
      (fail state :preflight (failure-detail result :deploy/context-unavailable))
      (assoc state :target (:target result)))))

(defn- ^{:stratum 1} render-manifests
  [state]
  (let [result ((get-in state [:operations :render!]) (:target state))
        rendered (:stdout result)]
    (if (or (schema/failed? result) (str/blank? rendered))
      (fail state :preflight (failure-detail result :deploy/render-failed))
      (assoc state :rendered-yaml rendered))))

(defn- ^{:stratum 1} server-dry-run
  [state]
  (let [result ((get-in state [:operations :dry-run!])
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
      (assoc state :rollback-info (:rollback-info result)))))

(defn- ^{:stratum 1} prepare-authority
  [state]
  (let [preflight {:rollback-info (:rollback-info state)
                   :rendered-yaml (:rendered-yaml state)
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

(defn- ^{:stratum 1} commit-effect
  [state]
  (let [committed (transaction/commit!
                   (:context state) (:transaction state) (:authority state)
                   (:operations state) (:now state))]
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} transact!
  "Resolve, preflight, authorize, persist, commit, and reconcile one deploy."
  [context deploy-config operations ^Instant now]
  (-> {:context context
       :deploy-config deploy-config
       :operations operations
       :now now}
      (advance resolve-target)
      (advance render-manifests)
      (advance server-dry-run)
      (advance capture-rollback)
      (advance prepare-authority)
      (advance propose-effect)
      (advance require-permission)
      (advance commit-effect)
      (advance reconcile-effect)
      outcome/from-state))
