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
(ns ai.miniforge.phase-deployment.deploy-test
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase-deployment.deploy :as deploy]
            [ai.miniforge.phase-deployment.deploy-config :as config]
            [ai.miniforge.phase-deployment.deploy-governed :as governed]
            [ai.miniforge.phase-deployment.deploy-provider :as provider]
            [ai.miniforge.phase-deployment.shell :as shell]
            [ai.miniforge.schema.interface :as schema]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} rollback-target
  {:deployment-name "api"
   :namespace "production"
   :context "cluster-1"})

(def ^{:stratum 0} rollback-error "kubectl unavailable")

(def ^{:stratum 0} rollback-info
  {:revision "7" :image "api:v7" :replicas 3})

(def ^{:stratum 0} deployment-config
  {:phase-config {}
   :kustomize-dir "/deploy"
   :namespace "production"
   :context "cluster-1"
   :app-label "api"
   :deployment-name "api"})

(deftest ^{:stratum 0} resolve-deploy-config-test
  (testing "deploy config normalizes workflow inputs and provision outputs once"
    (let [resolved (config/resolve-config
                    {:phase-config {:kustomize-dir "/cfg"
                                    :namespace "cfg-ns"
                                    :app-label "cfg-app"}
                     :execution/input {:namespace "prod"}
                     :execution/phase-results
                     {:provision {:result {:outputs {:gke_context "ctx-1"}}}}})]
      (is (= "/cfg" (:kustomize-dir resolved)))
      (is (= "prod" (:namespace resolved)))
      (is (= "ctx-1" (:context resolved)))
      (is (= "cfg-app" (:deployment-name resolved)))))
  (testing "deploy config preserves a context-free current-cluster target"
    (let [resolved (config/resolve-config
                    {:phase-config {:kustomize-dir "/cfg"}})]
      (is (= "default" (:namespace resolved)))
      (is (nil? (:context resolved))))))

(deftest ^{:stratum 0} build-pod-state-test
  (testing "pod state summaries preserve readiness and image details"
    (let [pod-state (provider/pod-state
                     [{:metadata {:name "api-1"}
                       :status {:phase "Running"
                                :containerStatuses [{:ready true} {:ready true}]}
                       :spec {:containers [{:image "svc:v1"} {:image "sidecar:v1"}]}}
                      {:metadata {:name "api-2"}
                       :status {:phase "Pending"
                                :containerStatuses [{:ready false}]}
                       :spec {:containers [{:image "svc:v2"}]}}
                      {}])]
      (is (= 3 (:pod-count pod-state)))
      (is (= 1 (:ready-count pod-state)))
      (is (= ["svc:v1" "sidecar:v1"]
             (get-in pod-state [:pods 0 :images])))
      (is (every? false? (mapv :ready? (subvec (:pods pod-state) 1)))))))

(deftest ^{:stratum 0} context-free-target-is-bound-once-test
  (let [calls (atom 0)]
    (with-redefs [shell/kubectl!
                  (fn [& _]
                    (swap! calls inc)
                    (schema/success :stdout "configured-cluster\n"))]
      (let [resolved (:target (provider/target!
                               {:context nil :namespace "production"}))]
        (is (= "configured-cluster" (:context resolved)))
        (is (= 1 @calls))))))

(deftest ^{:stratum 0} absent-current-context-fails-closed-test
  (let [kubectl-result (schema/success :stdout "\n")]
    (with-redefs [shell/kubectl! (fn [& _] kubectl-result)]
      (let [result (provider/target! {:context nil})]
        (is (schema/failed? result))
        (is (= kubectl-result (:kubectl-result result)))))))

(deftest ^{:stratum 0} current-context-shell-failure-keeps-target-contract-test
  (let [kubectl-result (schema/failure :stdout "kubectl unavailable")]
    (with-redefs [shell/kubectl! (fn [& _] kubectl-result)]
      (let [result (provider/target! {:context nil})]
        (is (schema/failed? result))
        (is (contains? result :target))
        (is (= kubectl-result (:kubectl-result result)))))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} rollout-failure
  {:deploy/status :failed
   :deploy/stage :observe
   :deploy/rollback-info rollback-info
   :deploy/rendered-yaml "image: api:v8"
   :deploy/failure "rollout timed out"})

(deftest ^{:stratum 1} rollback-shell-failure-keeps-provider-result-test
  (let [kubectl-result (schema/failure :parsed rollback-error)]
    (with-redefs [shell/kubectl! (fn [& _] kubectl-result)]
      (let [result (provider/rollback-info! rollback-target)]
        (is (schema/failed? result))
        (is (= kubectl-result (:kubectl-result result)))))))

(deftest ^{:stratum 1} invalid-rollback-shape-returns-failure-test
  (let [kubectl-result
        (schema/success
         :parsed
         {:metadata {:annotations {"deployment.kubernetes.io/revision" "4"}}
          :spec {:template {:spec {:containers [{:image "api:v4"}]}}}
          :status {:readyReplicas "three"}})]
    (with-redefs [shell/kubectl! (fn [& _] kubectl-result)]
      (let [result (provider/rollback-info! rollback-target)]
        (is (schema/failed? result))
        (is (some? (:validation result)))))))

(deftest ^{:stratum 1} unavailable-pod-observation-does-not-match-test
  (with-redefs [shell/kubectl-rollout-status!
                (constantly (schema/success :stdout "ready"))
                shell/kubectl-get-pods!
                (constantly (schema/failure :parsed "unavailable"))]
    (is (false? (:provider/matched?
                 (provider/observe! deployment-config))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} phase-failure-retains-rollback-evidence-test
  (let [[logger entries] (log/collecting-logger)]
    (with-redefs [governed/transact! (constantly rollout-failure)]
      (let [ctx (deploy/enter-deploy
                 {:execution/logger logger
                  :execution/input (select-keys deployment-config
                                                [:kustomize-dir :namespace
                                                 :context :app-label])})
            evidence-types (mapv :evidence/type (:execution/evidence ctx))]
        (is (= :failed (get-in ctx [:phase :status])))
        (is (= :rollout-failed (get-in ctx [:phase :result :status])))
        (is (= [:deploy/applied :deploy/rollout-failed]
               (mapv :log/event @entries)))
        (is (some #{:evidence/rollback-info} evidence-types))))))
