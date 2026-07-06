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

(ns ai.miniforge.phase-deployment.policy-test
  (:require
   [ai.miniforge.phase-deployment.policy :as sut]
   [ai.miniforge.policy-pack.interface :as pp]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Deployment policy tests

(deftest check-resource-count-test
  (testing "returns a violation when create count exceeds the configured limit"
    (let [result (sut/check-resource-count {:steps (repeat 3 {:op "create"})}
                                           {:max-resources 2})]
      (is (= :deploy/resource-count-limit (:violation/rule-id result)))
      (is (= 3 (get-in result [:violation/data :creates])))))

  (testing "returns nil when create count stays within the limit"
    (is (nil? (sut/check-resource-count {:steps (repeat 2 {:op "create"})}
                                        {:max-resources 2})))))

(deftest check-gke-node-limit-test
  (testing "counts node pools in Pulumi preview data"
    (let [result (sut/check-gke-node-limit
                  {:steps [{:op "create" :type "gcp:container/nodePool:NodePool"}
                           {:op "create" :type "gcp:container/nodePool:NodePool"}]}
                  {:max-nodes 1})]
      (is (= :deploy/gke-node-limit (:violation/rule-id result)))
      (is (= 2 (get-in result [:violation/data :node-pools])))))

  (testing "accepts preview JSON using capitalized Pulumi keys"
    (let [result (sut/check-gke-node-limit
                  {:Steps [{:Op "create" :type "gcp:container/nodePool:NodePool"}]}
                  {:max-nodes 2})]
      (is (nil? result)))))

;------------------------------------------------------------------------------ Layer 1
;; Deployment policy pack supplied to the provision gate

(deftest deployment-policy-packs-is-non-empty
  (testing "the shipped deployment-safety pack is loaded and supplied — so the
            fail-closed :policy-pack gate has policy to evaluate"
    (let [packs (sut/deployment-policy-packs)]
      (is (= 1 (count packs)))
      (is (= :miniforge/deployment-safety (:pack/id (first packs)))))))

(deftest deployment-pack-blocks-destructive-preview
  (testing "a destructive Pulumi preview trips a :hard-halt rule (proving the
            :block->:hard-halt fix — the rule now actually blocks)"
    (let [packs   (sut/deployment-policy-packs)
          preview (json/generate-string {:steps [{:op "delete" :type "gcp:sql:Instance"}]})
          result  (pp/check-artifact packs
                                     {:artifact/content preview :artifact/path "preview.json"}
                                     {:phase :provision})]
      (is (false? (:passed? result)))
      (is (seq (:blocking result))))))

(deftest deployment-pack-passes-benign-preview
  (testing "a create-only preview under limits passes the pack"
    (let [packs   (sut/deployment-policy-packs)
          preview (json/generate-string {:steps [{:op "create" :type "gcp:storage:Bucket"}]})
          result  (pp/check-artifact packs
                                     {:artifact/content preview :artifact/path "preview.json"}
                                     {:phase :provision})]
      (is (true? (:passed? result)))
      (is (empty? (:blocking result))))))
