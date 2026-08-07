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
(ns ai.miniforge.phase-deployment.deploy-flow-test
  (:require
   [ai.miniforge.phase-deployment.deploy :as deploy]
   [ai.miniforge.phase-deployment.deploy-flow :as flow]
   [ai.miniforge.phase-deployment.deploy-provider :as provider]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} deploy-config
  {:phase-config {}
   :kustomize-dir "/deploy"
   :namespace "production"
   :context "cluster-1"
   :default-context "cluster-1"
   :app-label "api"
   :deployment-name "api"})

(def ^{:stratum 0} rollback-info
  {:revision "7" :image "api:v7" :replicas 3})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} apply-failure-preserves-rollback-test
  (with-redefs [provider/rollback-info! (constantly rollback-info)
                provider/apply! (constantly {:success? false
                                             :error "apply refused"})]
    (let [deployment (flow/execute! deploy-config)]
      (is (= :failed (:deploy/status deployment)))
      (is (= :apply (:deploy/stage deployment)))
      (is (= rollback-info (:deploy/rollback-info deployment))))))

(deftest ^{:stratum 1} phase-failure-retains-rollback-evidence-test
  (with-redefs [flow/execute!
                (constantly {:deploy/status :failed
                             :deploy/stage :observe
                             :deploy/rollback-info rollback-info
                             :deploy/rendered-yaml "image: api:v8"
                             :deploy/failure "rollout timed out"})]
    (let [ctx (deploy/enter-deploy
               {:execution/input (select-keys deploy-config
                                              [:kustomize-dir :namespace
                                               :context :app-label])})
          evidence-types (mapv :evidence/type (:execution/evidence ctx))]
      (is (= :failed (get-in ctx [:phase :status])))
      (is (some #{:evidence/rollback-info} evidence-types)))))
