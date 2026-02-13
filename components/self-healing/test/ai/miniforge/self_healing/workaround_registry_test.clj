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

(ns ai.miniforge.self-healing.workaround-registry-test
  "Unit tests for workaround registry."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [ai.miniforge.self-healing.workaround-registry :as registry]))

;;------------------------------------------------------------------------------ Test fixtures

(def test-registry-path
  (str (System/getProperty "user.home") "/.miniforge/test_workarounds.edn"))

(defn cleanup-test-registry
  [f]
  (with-redefs [registry/workaround-registry-path (constantly test-registry-path)]
    (try
      ;; Clean before test to ensure isolation
      (when (.exists (io/file test-registry-path))
        (.delete (io/file test-registry-path)))
      (f)
      (finally
        (when (.exists (io/file test-registry-path))
          (.delete (io/file test-registry-path)))))))

(use-fixtures :each cleanup-test-registry)

;;------------------------------------------------------------------------------ Layer 0 Tests
;; Basic load/save operations

(deftest test-load-workarounds-empty
  (testing "Load workarounds from non-existent file returns empty registry"
    (let [result (registry/load-workarounds)]
      (is (map? result))
      (is (= [] (:workarounds result))))))

(deftest test-save-and-load-roundtrip
  (testing "Save and load workarounds roundtrip"
    (let [data {:workarounds [{:id (java.util.UUID/randomUUID)
                               :error-pattern-id :test-pattern
                               :description "Test workaround"
                               :workaround-type :retry
                               :workaround-data {}
                               :success-count 5
                               :failure-count 1
                               :confidence 0.833}]}]
      (registry/save-workarounds! data)
      (let [loaded (registry/load-workarounds)]
        (is (= 1 (count (:workarounds loaded))))
        (is (= :test-pattern (-> loaded :workarounds first :error-pattern-id)))))))

;;------------------------------------------------------------------------------ Layer 1 Tests
;; Add workaround

(deftest test-add-workaround
  (testing "Add workaround generates ID and timestamps"
    (let [workaround {:error-pattern-id :anthropic-rate-limit
                      :description "Retry with exponential backoff"
                      :workaround-type :retry
                      :workaround-data {:max-retries 3 :backoff-ms 1000}
                      :github-issue-url "https://github.com/..."}
          result (registry/add-workaround! workaround)]
      (is (uuid? (:id result)))
      (is (= :anthropic-rate-limit (:error-pattern-id result)))
      (is (= 0 (:success-count result)))
      (is (= 0 (:failure-count result)))
      (is (= 0.0 (:confidence result)))
      (is (some? (:discovered-at result)))
      (is (nil? (:last-used result))))))

(deftest test-add-workaround-with-id
  (testing "Add workaround preserves provided ID"
    (let [id (java.util.UUID/randomUUID)
          workaround {:id id
                      :error-pattern-id :test-pattern
                      :description "Test"
                      :workaround-type :retry
                      :workaround-data {}}
          result (registry/add-workaround! workaround)]
      (is (= id (:id result))))))

;;------------------------------------------------------------------------------ Layer 2 Tests
;; Update statistics

(deftest test-update-workaround-stats-success
  (testing "Update workaround stats on success"
    (let [workaround (registry/add-workaround!
                      {:error-pattern-id :test-pattern
                       :description "Test"
                       :workaround-type :retry
                       :workaround-data {}})
          id (:id workaround)
          updated (registry/update-workaround-stats! id true)]
      (is (= 1 (:success-count updated)))
      (is (= 0 (:failure-count updated)))
      (is (= 1.0 (:confidence updated)))
      (is (some? (:last-used updated))))))

(deftest test-update-workaround-stats-failure
  (testing "Update workaround stats on failure"
    (let [workaround (registry/add-workaround!
                      {:error-pattern-id :test-pattern
                       :description "Test"
                       :workaround-type :retry
                       :workaround-data {}})
          id (:id workaround)
          updated (registry/update-workaround-stats! id false)]
      (is (= 0 (:success-count updated)))
      (is (= 1 (:failure-count updated)))
      (is (= 0.0 (:confidence updated))))))

(deftest test-update-workaround-stats-confidence
  (testing "Update workaround stats calculates confidence correctly"
    (let [workaround (registry/add-workaround!
                      {:error-pattern-id :test-pattern
                       :description "Test"
                       :workaround-type :retry
                       :workaround-data {}})
          id (:id workaround)]
      ;; 3 successes
      (registry/update-workaround-stats! id true)
      (registry/update-workaround-stats! id true)
      (registry/update-workaround-stats! id true)
      ;; 1 failure
      (let [updated (registry/update-workaround-stats! id false)]
        (is (= 3 (:success-count updated)))
        (is (= 1 (:failure-count updated)))
        (is (= 0.75 (:confidence updated)))))))

(deftest test-update-workaround-stats-not-found
  (testing "Update workaround stats returns nil for non-existent ID"
    (let [result (registry/update-workaround-stats! (java.util.UUID/randomUUID) true)]
      (is (nil? result)))))

;;------------------------------------------------------------------------------ Layer 3 Tests
;; Query operations

(deftest test-get-workaround-by-pattern
  (testing "Get workaround by pattern ID"
    (registry/add-workaround!
     {:error-pattern-id :pattern-1
      :description "Test 1"
      :workaround-type :retry
      :workaround-data {}})
    (registry/add-workaround!
     {:error-pattern-id :pattern-2
      :description "Test 2"
      :workaround-type :backend-switch
      :workaround-data {}})
    (let [result (registry/get-workaround-by-pattern :pattern-1)]
      (is (some? result))
      (is (= :pattern-1 (:error-pattern-id result)))
      (is (= "Test 1" (:description result))))))

(deftest test-get-workaround-by-pattern-not-found
  (testing "Get workaround by pattern returns nil for non-existent pattern"
    (let [result (registry/get-workaround-by-pattern :non-existent)]
      (is (nil? result)))))

(deftest test-get-high-confidence-workarounds
  (testing "Get high-confidence workarounds filters by confidence >= 0.8"
    (let [wa1 (registry/add-workaround!
               {:error-pattern-id :high-conf
                :description "High confidence"
                :workaround-type :retry
                :workaround-data {}})
          wa2 (registry/add-workaround!
               {:error-pattern-id :low-conf
                :description "Low confidence"
                :workaround-type :retry
                :workaround-data {}})]
      ;; Make wa1 high confidence: 4 successes, 1 failure = 0.8
      (registry/update-workaround-stats! (:id wa1) true)
      (registry/update-workaround-stats! (:id wa1) true)
      (registry/update-workaround-stats! (:id wa1) true)
      (registry/update-workaround-stats! (:id wa1) true)
      (registry/update-workaround-stats! (:id wa1) false)
      ;; Make wa2 low confidence: 1 success, 2 failures = 0.33
      (registry/update-workaround-stats! (:id wa2) true)
      (registry/update-workaround-stats! (:id wa2) false)
      (registry/update-workaround-stats! (:id wa2) false)
      (let [high-conf (registry/get-high-confidence-workarounds)]
        (is (= 1 (count high-conf)))
        (is (= :high-conf (-> high-conf first :error-pattern-id)))))))

(deftest test-get-all-workarounds
  (testing "Get all workarounds returns complete list"
    (registry/add-workaround!
     {:error-pattern-id :pattern-1
      :description "Test 1"
      :workaround-type :retry
      :workaround-data {}})
    (registry/add-workaround!
     {:error-pattern-id :pattern-2
      :description "Test 2"
      :workaround-type :backend-switch
      :workaround-data {}})
    (let [all (registry/get-all-workarounds)]
      (is (= 2 (count all))))))

(deftest test-delete-workaround
  (testing "Delete workaround removes it from registry"
    (let [workaround (registry/add-workaround!
                      {:error-pattern-id :to-delete
                       :description "Will be deleted"
                       :workaround-type :retry
                       :workaround-data {}})
          id (:id workaround)
          deleted? (registry/delete-workaround! id)]
      (is (true? deleted?))
      (is (nil? (registry/get-workaround-by-pattern :to-delete))))))

(deftest test-delete-workaround-not-found
  (testing "Delete non-existent workaround returns nil"
    (let [result (registry/delete-workaround! (java.util.UUID/randomUUID))]
      (is (nil? result)))))
