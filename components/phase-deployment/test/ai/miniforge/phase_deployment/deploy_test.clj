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
  (:require [ai.miniforge.phase-deployment.deploy-config :as config]
            [ai.miniforge.phase-deployment.deploy-provider :as provider]
            [ai.miniforge.phase-deployment.shell :as shell]
            [ai.miniforge.schema.interface :as schema]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

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
  (with-redefs [shell/kubectl! (fn [& _] (schema/success :stdout "\n"))]
    (is (schema/failed? (provider/target! {:context nil})))))

(deftest ^{:stratum 0} current-context-shell-failure-keeps-target-contract-test
  (let [kubectl-result (schema/failure :stdout "kubectl unavailable")]
    (with-redefs [shell/kubectl! (fn [& _] kubectl-result)]
      (let [result (provider/target! {:context nil})]
        (is (schema/failed? result))
        (is (contains? result :target))
        (is (= kubectl-result (:kubectl-result result)))))))
