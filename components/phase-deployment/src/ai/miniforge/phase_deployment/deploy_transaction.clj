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
(ns ai.miniforge.phase-deployment.deploy-transaction
  "Durable commit and reconciliation for one exact deployment proposal."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.phase-deployment.messages :as msg]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-store-dir-property ".miniforge/effects")

(def ^{:stratum 0} target-keys
  [:kustomize-dir :context :namespace :deployment-name :app-label])

(def ^{:stratum 0} provider-result-keys
  [:success? :exit-code :stdout :stderr :error])

(defn- ^{:stratum 0} failure-detail
  [result]
  (str (or (not-empty (:stderr result))
           (:error result)
           (msg/t :deploy/apply-failed))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} store-dir
  [context]
  (or (:effect-store-dir context)
      (str (System/getProperty "user.home") "/" default-store-dir-property)))

(defn- ^{:stratum 1} apply-report
  [operations proposal]
  (let [result ((:apply-rendered! operations)
                (select-keys proposal target-keys)
                (:deploy/rendered-yaml proposal))]
    (if (schema/failed? result)
      {:effect/outcome :failed
       :effect/failure (failure-detail result)
       :effect/observed
       {:deploy/rollback-info (:deploy/rollback-info proposal)
        :provider/result (select-keys result provider-result-keys)}}
      {:effect/outcome :accepted
       :effect/observed {:provider/result (select-keys result provider-result-keys)}})))

(defn- ^{:stratum 1} probe-answer
  [operations proposal _transaction]
  (let [observation ((:observe! operations) (select-keys proposal target-keys))]
    {:effect/observed (:provider/observed observation)
     :effect/matched? (:provider/matched? observation)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} propose!
  [context authority now]
  (fx/propose!
   (store-dir context)
   {:effect-id (:effect/id authority)
    :effect-class :effect/deploy
    :grant-id (get-in authority [:authority/grant :grant/id])
    :envelope-id (get-in authority [:authority/envelope :envelope/id])
    :proposal (:effect/proposal authority)}
   now))

(defn ^{:stratum 2} commit!
  [context transaction authority operations now]
  (let [dir (store-dir context)
        durable (fx/read-record dir (:effect/id transaction))]
    (if (anomaly/anomaly? durable)
      durable
      (fx/commit! dir transaction (:authority/grant authority) {} now
                  (partial apply-report operations
                           (:effect/proposal durable))))))

(defn ^{:stratum 2} reconcile!
  [context transaction operations now]
  (if-not (contains? fx/reconcilable-states (:effect/state transaction))
    transaction
    (let [dir (store-dir context)
          durable (fx/read-record dir (:effect/id transaction))]
      (if (anomaly/anomaly? durable)
        durable
        (fx/reconcile! dir transaction
                       (partial probe-answer operations
                                (:effect/proposal durable))
                       now)))))
