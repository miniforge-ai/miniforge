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

(ns ai.miniforge.phase.deploy.deploy-test
  (:require [ai.miniforge.phase.deploy.deploy :as sut]
            [clojure.test :refer [deftest is testing]]))

(deftest resolve-deploy-config-test
  (testing "deploy config normalizes workflow inputs and provision outputs once"
    (let [resolved (#'sut/resolve-deploy-config
                    {:phase-config {:kustomize-dir "/cfg"
                                    :namespace "cfg-ns"
                                    :app-label "cfg-app"}
                     :execution/input {:namespace "prod"}
                     :execution/phase-results {:provision {:result {:outputs {:gke_context "ctx-1"}}}}})]
      (is (= "/cfg" (:kustomize-dir resolved)))
      (is (= "prod" (:namespace resolved)))
      (is (= "ctx-1" (:context resolved)))
      (is (= "cfg-app" (:deployment-name resolved))))))

(deftest build-pod-state-test
  (testing "pod state summaries preserve readiness and image details"
    (let [pod-state (#'sut/build-pod-state
                     [{:metadata {:name "api-1"}
                       :status {:phase "Running"
                                :containerStatuses [{:ready true} {:ready true}]}
                       :spec {:containers [{:image "svc:v1"} {:image "sidecar:v1"}]}}
                      {:metadata {:name "api-2"}
                       :status {:phase "Pending"
                                :containerStatuses [{:ready false}]}
                       :spec {:containers [{:image "svc:v2"}]}}])]
      (is (= 2 (:pod-count pod-state)))
      (is (= 1 (:ready-count pod-state)))
      (is (= ["svc:v1" "sidecar:v1"]
             (get-in pod-state [:pods 0 :images])))
      (is (false? (get-in pod-state [:pods 1 :ready?]))))))
