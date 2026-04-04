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

(ns ai.miniforge.repo-dag.dag-integration-test
  "Integration tests exercising realistic multi-layer DAG workflows."
  (:require [clojure.test :as test :refer [deftest testing is use-fixtures]]
            [ai.miniforge.repo-dag.interface :as dag]))

;------------------------------------------------------------------------------ Fixtures

(def ^:dynamic *manager* nil)

(defn manager-fixture [f]
  (binding [*manager* (dag/create-manager)]
    (f)))

(use-fixtures :each manager-fixture)

;------------------------------------------------------------------------------ Helpers

(defn- build-infra-dag!
  "Builds a realistic infrastructure DAG:
   tf-modules -> tf-live -> k8s -> argocd -> app1
                                          -> app2"
  [manager]
  (let [d (dag/create-dag manager "infra-pipeline" "Full infrastructure pipeline")]
    (dag/add-repo manager (:dag/id d)
                  {:repo/url "https://github.com/org/tf-modules" :repo/name "tf-modules"
                   :repo/type :terraform-module})
    (dag/add-repo manager (:dag/id d)
                  {:repo/url "https://github.com/org/tf-live" :repo/name "tf-live"
                   :repo/type :terraform-live})
    (dag/add-repo manager (:dag/id d)
                  {:repo/url "https://github.com/org/k8s" :repo/name "k8s"
                   :repo/type :kubernetes})
    (dag/add-repo manager (:dag/id d)
                  {:repo/url "https://github.com/org/argocd" :repo/name "argocd"
                   :repo/type :argocd})
    (dag/add-repo manager (:dag/id d)
                  {:repo/url "https://github.com/org/app1" :repo/name "app1"
                   :repo/type :application})
    (dag/add-repo manager (:dag/id d)
                  {:repo/url "https://github.com/org/app2" :repo/name "app2"
                   :repo/type :application})
    (dag/add-edge manager (:dag/id d) "tf-modules" "tf-live" :module-before-live :sequential)
    (dag/add-edge manager (:dag/id d) "tf-live" "k8s" :library-before-consumer :sequential)
    (dag/add-edge manager (:dag/id d) "k8s" "argocd" :library-before-consumer :sequential)
    (dag/add-edge manager (:dag/id d) "argocd" "app1" :library-before-consumer :sequential)
    (dag/add-edge manager (:dag/id d) "argocd" "app2" :library-before-consumer :sequential)
    (:dag/id d)))

;------------------------------------------------------------------------------ Integration tests

(deftest full-pipeline-topo-order-test
  (testing "realistic infra DAG produces valid topological order"
    (let [dag-id (build-infra-dag! *manager*)
          result (dag/compute-topo-order *manager* dag-id)
          order (:order result)]
      (is (:success result))
      (is (= 6 (count order)))
      ;; tf-modules must be first
      (is (= "tf-modules" (first order)))
      ;; Verify all ordering constraints
      (is (< (.indexOf order "tf-modules") (.indexOf order "tf-live")))
      (is (< (.indexOf order "tf-live") (.indexOf order "k8s")))
      (is (< (.indexOf order "k8s") (.indexOf order "argocd")))
      (is (< (.indexOf order "argocd") (.indexOf order "app1")))
      (is (< (.indexOf order "argocd") (.indexOf order "app2"))))))

(deftest full-pipeline-affected-repos-test
  (testing "change to tf-modules affects all downstream"
    (let [dag-id (build-infra-dag! *manager*)
          affected (dag/affected-repos *manager* dag-id "tf-modules")]
      (is (= #{"tf-live" "k8s" "argocd" "app1" "app2"} affected))))

  (testing "change to argocd affects only apps"
    (let [dag-id (build-infra-dag! *manager*)
          affected (dag/affected-repos *manager* dag-id "argocd")]
      (is (= #{"app1" "app2"} affected))))

  (testing "change to app1 affects nothing"
    (let [dag-id (build-infra-dag! *manager*)
          affected (dag/affected-repos *manager* dag-id "app1")]
      (is (= #{} affected)))))

(deftest full-pipeline-upstream-repos-test
  (testing "app1 depends on entire chain"
    (let [dag-id (build-infra-dag! *manager*)
          upstream (dag/upstream-repos *manager* dag-id "app1")]
      (is (= #{"tf-modules" "tf-live" "k8s" "argocd"} upstream))))

  (testing "tf-modules has no upstream dependencies"
    (let [dag-id (build-infra-dag! *manager*)
          upstream (dag/upstream-repos *manager* dag-id "tf-modules")]
      (is (= #{} upstream)))))

(deftest full-pipeline-merge-order-test
  (testing "merge order for subset respects dependencies"
    (let [dag-id (build-infra-dag! *manager*)
          result (dag/merge-order *manager* dag-id #{"tf-modules" "k8s" "app1"})]
      (is (:success result))
      (let [order (:order result)]
        (is (= 3 (count order)))
        (is (< (.indexOf order "tf-modules") (.indexOf order "k8s")))
        (is (< (.indexOf order "k8s") (.indexOf order "app1")))))))

(deftest full-pipeline-validation-test
  (testing "realistic DAG passes validation"
    (let [dag-id (build-infra-dag! *manager*)
          result (dag/validate-dag *manager* dag-id)]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest full-pipeline-layers-test
  (testing "realistic DAG has repos in correct layers"
    (let [dag-id (build-infra-dag! *manager*)
          current-dag (dag/get-dag *manager* dag-id)
          layers (dag/compute-layers current-dag)]
      (is (= ["tf-modules"] (:foundations layers)))
      (is (= ["tf-live"] (:infrastructure layers)))
      (is (= #{"k8s" "argocd"} (set (:platform layers))))
      (is (= #{"app1" "app2"} (set (:application layers)))))))

(deftest remove-middle-node-test
  (testing "removing middle node breaks chain but keeps other repos"
    (let [dag-id (build-infra-dag! *manager*)
          updated (dag/remove-repo *manager* dag-id "k8s")]
      (is (= 5 (count (:dag/repos updated))))
      ;; Edges involving k8s should be removed (tf-live->k8s and k8s->argocd)
      (let [edge-pairs (set (map (juxt :edge/from :edge/to) (:dag/edges updated)))]
        (is (not (contains? edge-pairs ["tf-live" "k8s"])))
        (is (not (contains? edge-pairs ["k8s" "argocd"])))
        ;; Other edges should remain
        (is (contains? edge-pairs ["tf-modules" "tf-live"]))
        (is (contains? edge-pairs ["argocd" "app1"]))
        (is (contains? edge-pairs ["argocd" "app2"]))))))

(deftest multiple-dags-independence-test
  (testing "operations on one DAG do not affect another"
    (let [dag1-id (build-infra-dag! *manager*)
          d2 (dag/create-dag *manager* "other-dag")
          _ (dag/add-repo *manager* (:dag/id d2)
                          {:repo/url "https://github.com/x" :repo/name "x" :repo/type :library})]
      ;; dag1 should still have 6 repos
      (is (= 6 (count (:dag/repos (dag/get-dag *manager* dag1-id)))))
      ;; dag2 should have 1 repo
      (is (= 1 (count (:dag/repos (dag/get-dag *manager* (:dag/id d2)))))))))
