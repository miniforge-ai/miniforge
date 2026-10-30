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
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.phase-deployment.evidence :as evidence]
   [ai.miniforge.phase-deployment.messages :as msg]
   [ai.miniforge.schema.interface :as schema])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-store-dir-relative-path
  "The effect store's directory under the miniforge home."
  "effects")

(def ^{:stratum 0} target-keys
  [:kustomize-dir :context :namespace :deployment-name :app-label])

(def ^{:stratum 0} provider-result-keys [:success? :exit-code :stdout :stderr :error])

(defn- ^{:stratum 0} failure-detail
  [result]
  (str (or (not-empty (:stderr result))
           (:error result)
           (msg/t :deploy/apply-failed))))

(defn- ^{:stratum 0} issued-grant
  "Read the registered grant this attempt issued; any other id is refused."
  [grant-dir issued-id requested-id]
  (if (= issued-id requested-id)
    (grant/current grant-dir requested-id)
    (anomaly/anomaly :unauthorized (msg/t :deploy/grant-substituted) {})))

(defn- ^{:stratum 0} clock
  "The commit-time clock: the context's when supplied, else the system's."
  [context]
  (get context :clock #(Instant/now)))

(defn ^{:stratum 0} current-time
  "A fresh reading of the context's clock, else the system's."
  [context]
  ((get context :clock #(Instant/now))))

(defn- ^{:stratum 0} running-proposed-images?
  "True when the observed pods run at least one image the proposal
   rendered: an old deployment on other images does not. A kustomization
   may render other workloads' or init-container images, and a mesh may
   inject sidecars, so the check asks for overlap, not equality. A
   proposal with no images, or one that keeps the running image, cannot
   be told apart from the old deployment this way."
  [proposal observed]
  (let [running (set (mapcat :images (get-in observed [:deployment/pods :pods])))
        wanted (map :image (evidence/extract-image-digests
                            (str (:deploy/rendered-yaml proposal))))]
    (or (empty? wanted) (boolean (some running wanted)))))

(defn ^{:stratum 0} spent?
  "A deploy record that definitely did not leave the proposal running: it
   failed, or a healthy rollout ran other images."
  [t]
  (or (= :failed (:effect/state t))
      (and (= :reconciled (:effect/state t)) (false? (:effect/matched? t)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} store-dir
  [context]
  (or (:effect-store-dir context)
      (str (config/miniforge-home) "/" default-store-dir-relative-path)))

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
  "The cluster's answer. A healthy rollout settles the record: matched
   when it runs the proposed images, a mismatch when it runs others (the
   apply never happened). An unhealthy one settles nothing."
  [operations proposal _transaction]
  (let [observation ((:observe! operations) (select-keys proposal target-keys))
        observed (:provider/observed observation)
        healthy? (true? (:provider/matched? observation))]
    {:effect/observed observed
     :effect/matched? (and healthy? (running-proposed-images? proposal observed))
     :deploy/settles? healthy?}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} propose!
  [context authority now]
  (fx/propose!
   (store-dir context)
   {:effect-id (:effect/id authority) :effect-class :effect/deploy
    :grant-id (get-in authority [:authority/grant :grant/id])
    :envelope-id (get-in authority [:authority/envelope :envelope/id])
    :proposal (:effect/proposal authority)}
   now))

(defn ^{:stratum 2} commit!
  "Re-read the registered grant, then apply the durable proposal. A grant
   absent from `grant-dir` returns an anomaly and a revoked one records
   `:failed`; neither applies anything."
  [context transaction grant-dir grant-id operations]
  (fx/commit-current! (store-dir context) (:effect/id transaction)
                      (partial issued-grant grant-dir grant-id)
                      (clock context)
                      #(apply-report operations (:effect/proposal %))))

(defn ^{:stratum 2} reconcile!
  "Observe the cluster and settle the record once the rollout is healthy.
   An unhealthy observation (a slow rollout, a failed read) settles
   nothing: the record stays reconcilable for a later attempt, and the
   returned copy carries the observation for this attempt's outcome. The
   provider call runs inside `fx/reconcile!`, so one that throws leaves
   the record reconcilable and returns its anomaly."
  [context transaction operations now]
  (if-not (contains? fx/reconcilable-states (:effect/state transaction))
    transaction
    (let [dir (store-dir context)
          durable (fx/read-record dir (:effect/id transaction))
          seen (atom nil)
          healthy-only (fn [t]
                         (let [answer (probe-answer operations (:effect/proposal durable) t)]
                           (reset! seen answer)
                           (when (:deploy/settles? answer)
                             (dissoc answer :deploy/settles?))))]
      (if (anomaly/anomaly? durable)
        durable
        (let [reconciled (fx/reconcile! dir transaction healthy-only now)]
          (if (and (anomaly/anomaly? reconciled) @seen)
            (assoc transaction :effect/observed (:effect/observed @seen))
            reconciled))))))
