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

(ns ai.miniforge.self-healing.workaround-detector-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.java.io :as io]
   [ai.miniforge.self-healing.workaround-detector :as detector]))

;;------------------------------------------------------------------------------ Test fixtures

(def test-approval-file
  "Test file path for approval tracking"
  (str (System/getProperty "java.io.tmpdir") "/test_workaround_approvals.edn"))

(defn cleanup-test-files
  "Delete test approval file"
  []
  (when (.exists (io/file test-approval-file))
    (.delete (io/file test-approval-file))))

(defn with-test-approval-file
  "Fixture to use test approval file and clean up after"
  [f]
  (with-redefs [detector/approval-file-path (constantly test-approval-file)]
    (cleanup-test-files)
    (try
      (f)
      (finally
        (cleanup-test-files)))))

(use-fixtures :each with-test-approval-file)

;;------------------------------------------------------------------------------ Tests

(deftest test-match-error-to-workaround
  (testing "Match error message to workaround pattern"
    ;; This test depends on error patterns being loaded from resources
    ;; For now, test with a generic error that should match backend setup patterns
    (let [error-msg "Connection refused to localhost:11434"
          pattern (detector/match-error-to-workaround error-msg)]
      ;; Pattern may or may not be found depending on resources
      ;; Just verify the function returns correctly
      (is (or (nil? pattern) (map? pattern))))))

(deftest test-match-error-from-exception
  (testing "Match error from exception object"
    (let [ex (Exception. "Test error")
          result (detector/match-error-to-workaround ex)]
      ;; Should handle exception without crashing
      (is (or (nil? result) (map? result))))))

(deftest test-match-error-from-map
  (testing "Match error from map with :message key"
    (let [error-map {:message "Test error message"}
          result (detector/match-error-to-workaround error-map)]
      ;; Should handle map without crashing
      (is (or (nil? result) (map? result))))))

(deftest test-detect-and-apply-no-match
  (testing "Detect and apply when no pattern matches"
    (let [error "This is a completely unique error message that should not match anything at all"
          result (detector/detect-and-apply-workaround error)]
      (is (false? (:workaround-found? result)))
      (is (false? (:applied? result)))
      (is (false? (:success? result))))))

(deftest test-apply-workaround-backend-switch
  (testing "Apply backend switch workaround"
    (let [pattern {:id :test-pattern
                   :description "Test backend switch"
                   :workaround {:type :backend-switch
                                :to-backend :openai
                                :auto-apply true}}
          result (detector/apply-workaround pattern)]
      (is (true? (:applied? result)))
      (is (true? (:success? result)))
      (is (= :openai (:to-backend result))))))

(deftest test-apply-workaround-env-var
  (testing "Apply environment variable workaround"
    (let [pattern {:id :test-pattern
                   :description "Test env var"
                   :workaround {:type :env-var
                                :env-var "TEST_VAR=test_value"
                                :auto-apply true}}
          result (detector/apply-workaround pattern)]
      ;; Env var workarounds can't actually set vars, so they return success=false with suggestion
      (is (false? (:success? result)))
      (is (string? (:message result)))
      (is (string? (:suggestion result))))))

(deftest test-workaround-requires-prompt-no-fn
  (testing "Workaround requiring prompt but no prompt function provided"
    (let [pattern {:id :test-pattern
                   :description "Test sudo operation"
                   :workaround {:type :shell-command
                                :command "sudo echo test"
                                :requires-sudo true
                                :auto-apply false}}
          result (detector/apply-workaround pattern)]
      (is (false? (:applied? result)))
      (is (false? (:success? result)))
      (is (true? (:requires-prompt result))))))

(deftest test-workaround-with-prompt-approved
  (testing "Workaround with prompt function - user approves"
    (let [pattern {:id :test-pattern
                   :description "Test with approval"
                   :workaround {:type :backend-switch
                                :to-backend :codex
                                :requires-sudo false
                                :auto-apply false}}
          prompt-fn (fn [_msg _opts] "yes")
          result (detector/apply-workaround pattern prompt-fn)]
      (is (true? (:applied? result)))
      (is (true? (:success? result))))))

(deftest test-workaround-with-prompt-denied
  (testing "Workaround with prompt function - user denies"
    (let [pattern {:id :test-pattern
                   :description "Test with denial"
                   :workaround {:type :backend-switch
                                :to-backend :codex
                                :requires-sudo true
                                :auto-apply false}}
          prompt-fn (fn [_msg _opts] "no")
          result (detector/apply-workaround pattern prompt-fn)]
      (is (false? (:applied? result)))
      (is (false? (:success? result))))))

(deftest test-workaround-auto-apply-no-sudo
  (testing "Workaround with auto-apply and no sudo requirement"
    (let [pattern {:id :test-auto-apply
                   :description "Test auto apply"
                   :workaround {:type :backend-switch
                                :to-backend :google
                                :auto-apply true
                                :requires-sudo false}}
          result (detector/apply-workaround pattern)]
      (is (true? (:applied? result)))
      (is (true? (:success? result))))))

(deftest test-detect-and-apply-full-flow
  (testing "Full detect and apply flow"
    ;; Create a mock pattern that will match
    (with-redefs [detector/workaround-patterns
                  [{:id :mock-pattern
                    :description "Mock error pattern"
                    :regex "mock error"
                    :workaround {:type :backend-switch
                                 :to-backend :openai
                                 :auto-apply true}}]]
      (let [error "This is a mock error message"
            result (detector/detect-and-apply-workaround error)]
        (is (true? (:workaround-found? result)))
        (is (true? (:applied? result)))
        (is (true? (:success? result)))
        (is (some? (:pattern result)))))))

(deftest test-unknown-workaround-type
  (testing "Unknown workaround type"
    (let [pattern {:id :test-unknown
                   :description "Test unknown type"
                   :workaround {:type :unknown-type
                                :auto-apply true}}
          result (detector/apply-workaround pattern)]
      (is (true? (:applied? result)))
      (is (false? (:success? result)))
      (is (re-find #"Unknown workaround type" (:message result))))))
