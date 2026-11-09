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
(ns ai.miniforge.agent-environment-kubernetes.manifests-test
  (:require
   [ai.miniforge.agent-environment-kubernetes.fixtures :as fx]
   [ai.miniforge.agent-environment-kubernetes.manifests :as manifests]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} kata-runtime {:runtime/class fx/kata :runtime/node-selector fx/kata-nodes})

(def ^{:stratum 0} default-runtime {:runtime/class nil :runtime/node-selector {}})

(deftest ^{:stratum 0} test-namespace-is-labelled-with-its-owner-and-generation
  (let [labels (get-in (manifests/namespace-manifest fx/target fx/request) [:metadata :labels])]
    (is (= manifests/managed-by (get labels "app.kubernetes.io/managed-by")))
    (is (= "1" (get labels "miniforge.ai/lease-generation")))
    (is (= (str (:environment/tenant-id fx/request)) (get labels "miniforge.ai/tenant-id")))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-pod-names-the-runtime-class-it-requires
  (let [spec (:spec (manifests/pod-manifest (fx/settings) kata-runtime fx/target fx/request))]
    (testing "given a dedicated-kernel runtime → its RuntimeClass and nodes, never the node default"
      (is (= fx/kata (:runtimeClassName spec)))
      (is (= fx/kata-nodes (:nodeSelector spec))))
    (testing "given any pod → no token, no host paths, non-root, no privilege escalation"
      (is (false? (:automountServiceAccountToken spec)))
      (is (every? :emptyDir (:volumes spec)))
      (is (true? (get-in spec [:securityContext :runAsNonRoot])))
      (is (false? (get-in spec [:containers 0 :securityContext :allowPrivilegeEscalation]))))
    (is (= {:cpu "500m" :memory "512Mi" :ephemeral-storage "1024Mi"} (get-in spec [:containers 0 :resources :limits])))))

(deftest ^{:stratum 1} test-node-default-runtime-omits-the-runtime-class
  (let [spec (:spec (manifests/pod-manifest (fx/settings) default-runtime fx/target fx/request))]
    (is (not (contains? spec :runtimeClassName)))
    (is (not (contains? spec :nodeSelector)))))
