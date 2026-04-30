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

(ns ai.miniforge.phase-deployment.policy-extra-test
  "Expanded coverage for phase-deployment policy detection. Existing
   policy_test.clj covers the smoke paths; this file fills out the matrix
   of preview shapes and capitalization variations Pulumi can produce."
  (:require [ai.miniforge.phase-deployment.policy :as sut]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 1
;; check-resource-count — defaults, capitalization, JSON input

(deftest check-resource-count-default-limit-test
  (testing "When :max-resources is omitted, the documented default of 20 applies"
    (is (some? (sut/check-resource-count
                 {:steps (vec (repeat 21 {:op "create"}))} {})))
    (is (nil? (sut/check-resource-count
                {:steps (vec (repeat 20 {:op "create"}))} {})))))

(deftest check-resource-count-counts-only-creates-test
  (testing "Non-create operations (update, delete, etc.) don't count toward the limit"
    (let [steps (concat (repeat 5 {:op "create"})
                        (repeat 5 {:op "update"})
                        (repeat 5 {:op "delete"}))]
      (is (nil? (sut/check-resource-count {:steps steps} {:max-resources 10}))))))

(deftest check-resource-count-handles-capitalized-keys-test
  (testing "Pulumi-capitalized :Steps and :Op keys are recognized"
    (let [creates (vec (repeat 25 {:Op "create" :type "gcp:compute:Instance"}))]
      (is (some? (sut/check-resource-count
                   {:Steps creates} {:max-resources 10}))))))

(deftest check-resource-count-parses-json-string-test
  (testing "When the preview content is a JSON string, it's parsed and counted"
    (let [json-content (json/generate-string
                         {:steps (repeat 25 {:op "create"})})]
      (is (some? (sut/check-resource-count json-content {:max-resources 10}))))))

(deftest check-resource-count-malformed-json-yields-no-violation-test
  (testing "Garbage string content parses to {} ⇒ zero creates ⇒ no violation"
    (is (nil? (sut/check-resource-count "not valid json" {:max-resources 1})))))

(deftest check-resource-count-violation-fields-test
  (testing "Violation record carries documented :violation/* fields"
    (let [r (sut/check-resource-count
              {:steps (vec (repeat 5 {:op "create"}))}
              {:max-resources 2})]
      (is (= :deploy/resource-count-limit (:violation/rule-id r)))
      (is (keyword? (:violation/severity r)))
      (is (string? (:violation/message r)))
      (is (= {:creates 5 :limit 2} (:violation/data r))))))

;------------------------------------------------------------------------------ Layer 1
;; check-gke-node-limit — boundary + non-NodePool + capitalization

(deftest check-gke-node-limit-default-test
  (testing "Default :max-nodes 10 is applied when not configured"
    (let [pools (vec (repeat 11 {:op "create" :type "gcp:container/nodePool:NodePool"}))]
      (is (some? (sut/check-gke-node-limit {:steps pools} {})))
      (is (nil? (sut/check-gke-node-limit
                  {:steps (vec (repeat 10 {:op "create"
                                           :type "gcp:container/nodePool:NodePool"}))}
                  {}))))))

(deftest check-gke-node-limit-ignores-non-nodepool-creates-test
  (testing "Non-NodePool creates don't count toward the node-pool limit"
    (let [steps [{:op "create" :type "gcp:container/nodePool:NodePool"}
                 {:op "create" :type "gcp:compute:Instance"}
                 {:op "create" :type "gcp:storage:Bucket"}
                 {:op "create" :type "gcp:container/nodePool:NodePool"}]]
      ;; max-nodes 5 ⇒ 2 NodePools < 5 ⇒ no violation
      (is (nil? (sut/check-gke-node-limit {:steps steps} {:max-nodes 5}))))))

(deftest check-gke-node-limit-violation-fields-test
  (testing "Violation record carries documented :violation/* fields"
    (let [r (sut/check-gke-node-limit
              {:steps (vec (repeat 5 {:op "create"
                                      :type "gcp:container/nodePool:NodePool"}))}
              {:max-nodes 2})]
      (is (= :deploy/gke-node-limit (:violation/rule-id r)))
      (is (= 5 (get-in r [:violation/data :node-pools])))
      (is (= 2 (get-in r [:violation/data :limit]))))))
