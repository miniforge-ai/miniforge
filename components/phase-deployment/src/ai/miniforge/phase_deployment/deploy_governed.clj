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
  "Granted, gated, and durably transacted Kubernetes application
   (Ariadne step 2d, deployment half).

   `enter-deploy` previously went config -> rollback capture ->
   `kustomize-apply!` with no policy gate at all, while
   `check-resource-count` and `check-gke-node-limit` sat in
   `policy.clj` uncalled. This is the seam that makes the mutation
   conditional: dry-run, check, issue authority, record durably, and
   only then apply.

   The provider is injected. A governance seam whose tests need a real
   cluster is a seam nobody tests."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.phase-deployment.deploy-authority :as authority]
   [ai.miniforge.phase-deployment.deploy-outcome :as outcome])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} store-dir
  "Effect store location. Never relative to the process working
   directory — a durable record written where nobody looks is not one."
  [context]
  (or (:effect-store-dir context)
      (str (System/getProperty "user.home") "/.miniforge/effects")))

(defn- ^{:stratum 0} refusal
  "A refusal names the stage it happened at. Hard-coding one stage
   makes a preflight denial read as an authority failure, which sends
   whoever is debugging it to the wrong place."
  [stage code detail]
  {:deploy/status :failed
   :deploy/stage stage
   :deploy/rollback-info nil
   :deploy/failure detail
   :deploy/refusal code})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} propose!
  "Record correlated authority and intent BEFORE any mutation. The
   proposal carries the dry-run render, so the record says what was
   about to change rather than only that something was."
  [context auth rendered now]
  (fx/propose! (store-dir context)
               {:effect-id (:effect/id auth)
                :effect-class :effect/deploy
                :grant-id (get-in auth [:authority/grant :grant/id])
                :envelope-id (get-in auth [:authority/envelope :envelope/id])
                :proposal (assoc (:effect/proposal auth)
                                 :deploy/dry-run rendered)}
               now))

(defn- ^{:stratum 1} commit!
  "Apply, then ask the provider what it observes.

   A failed apply is a definite `:failed` — the provider told us it did
   not take. Anything else defers to the observation, and an
   unobservable result stays `:accepted`, which the coordinator records
   as unknown rather than as success."
  [context t auth now apply! observe!]
  (fx/commit! (store-dir context) t (:authority/grant auth) {:usage/count 1} now
              (fn []
                (let [applied (apply!)]
                  (if (:deploy/failed? applied)
                    {:effect/outcome :failed
                     :effect/failure (str (:deploy/failure applied))
                     :effect/observed (:deploy/rollback-info applied)}
                    (let [observed (observe!)]
                      (if (:provider/matched? observed)
                        {:effect/outcome :succeeded
                         :effect/observed (:provider/observed observed)}
                        {:effect/outcome :accepted
                         :effect/observed (:provider/observed observed)})))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} transact!
  "Dry-run, check, authorize, record, and only then mutate.

   `provider` supplies `:dry-run!`, `:apply!`, `:observe!`, and
   `:rollback-info!`. Returns the deploy outcome map the flow already
   speaks, so the refusal paths look like every other failure to the
   caller — except that no kubectl mutation ran."
  [context deploy-config provider ^Instant now]
  (let [rendered ((:dry-run! provider) deploy-config)
        pre (authority/preflight rendered (:policy-context context {}))]
    (if (= :deny (:preflight/result pre))
      (refusal :preflight :deploy/preflight-denied
               (str "deployment preflight denied: "
                    (pr-str (:preflight/violations pre))))
      (let [rollback-info ((:rollback-info! provider) deploy-config)
            auth (authority/prepare context (random-uuid) deploy-config
                                    (:preflight/result pre) now)]
        (cond
          (anomaly/anomaly? auth)
          (refusal :authority :deploy/authority-unavailable (:anomaly/message auth))

          (not (authority/permitted? auth))
          (refusal :authority :deploy/authority-denied
                   (str "deploy authority denied: "
                        (get-in auth [:authority/envelope :envelope/decision])))

          :else
          (let [proposed (propose! context auth (:preflight/rendered pre) now)]
            (if (anomaly/anomaly? proposed)
              (refusal :proposal :deploy/proposal-failed (:anomaly/message proposed))
              (let [committed (commit! context proposed auth now
                                       #((:apply! provider) deploy-config)
                                       #((:observe! provider) deploy-config))]
                (outcome/from-state
                 {:rollback-info rollback-info
                  :rendered-yaml (:preflight/rendered pre)
                  :authority auth
                  :transaction committed})))))))))
