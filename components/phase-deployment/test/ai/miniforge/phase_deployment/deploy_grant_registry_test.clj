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
(ns ai.miniforge.phase-deployment.deploy-grant-registry-test
  "Scenario: a deploy grant that is unregistered, or revoked after issue,
   refuses the deploy before anything is applied. Commit re-reads the
   grant from the registry instead of trusting the copy issuance returned."
  (:require
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.phase-deployment.deploy-governed :as governed]
   [ai.miniforge.schema.interface :as schema]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} target
  {:kustomize-dir "/repo/k8s/overlays/prod" :namespace "prod" :context "gke-prod"
   :app-label "api" :deployment-name "api" :phase-config {}})

(defn- ^{:stratum 0} deploy-context []
  (let [tmp #(.getCanonicalPath
              (.toFile (Files/createTempDirectory % (into-array FileAttribute []))))]
    {:execution/id (random-uuid)
     :execution/phase-results {:provision {:result {:output {:steps []}}}}
     :effect-store-dir (tmp "registry-effects")
     :grant-breach-dir (tmp "registry-grants")}))

(defn- ^{:stratum 0} operations
  "Provider operations that resolve `target`, succeed, and count applies."
  [applies target]
  {:target! (fn [_] (schema/success :target target))
   :render! (fn [_] (schema/success :rendered-yaml "kind: ConfigMap\n"))
   :server-dry-run! (fn [_ _] (schema/success :stdout "configured (server dry run)"))
   :rollback-info! (fn [_] (schema/success :rollback-info {:revision "1"}))
   :apply-rendered! (fn [_ _] (swap! applies inc) (schema/success :stdout "applied"))
   :observe! (fn [_] {:provider/matched? true :provider/observed {}})})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} deploy-with-registration
  "Deploy once with `register` standing in for `grant/register!`."
  [register]
  (let [applies (atom 0)
        context (deploy-context)]
    (with-redefs [grant/register! register]
      {:result (governed/transact! context target (operations applies target)
                                   (Instant/now))
       :records (fx/list-records (:effect-store-dir context))
       :applies @applies})))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} unregistered-grant-refuses-deploy-test
  (testing "a grant issuance returned but the registry never stored"
    (let [{:keys [result applies]} (deploy-with-registration (fn [_dir issued] issued))]
      (is (= :failed (:deploy/status result)))
      (is (zero? applies)))))

(deftest ^{:stratum 2} revoked-grant-refuses-deploy-test
  (testing "a grant revoked in the registry between issue and commit"
    (let [register grant/register!
          {:keys [result records applies]}
          (deploy-with-registration
           (fn [dir issued]
             (let [registered (register dir issued)]
               (grant/revoke-stored! dir (:grant/id issued)
                                     :revocation/operator (Instant/now))
               registered)))]
      (is (= :failed (:deploy/status result)))
      (is (= [:failed] (mapv :effect/state records)))
      (is (zero? applies)))))
