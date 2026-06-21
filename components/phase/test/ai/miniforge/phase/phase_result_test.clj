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

(ns ai.miniforge.phase.phase-result-test
  (:require
   [ai.miniforge.phase.phase-result :as pr]
   [clojure.test :refer [deftest is testing]]))

(deftest blocked-builds-refusal-result-test
  (testing "blocked tags an error-shaped result with a RefusalReason cause"
    (let [result (pr/blocked "env-7" "spec missing" :missing-input
                             {:tokens 0 :duration-ms 5})]
      (is (pr/blocked? result))
      (is (= :missing-input (pr/blocked-reason result)))
      (is (= :error (:status result)) "block reuses the error halt path")
      (is (= "env-7" (:environment-id result))))))

(deftest blocked?-false-for-plain-results-test
  (testing "non-blocked results carry no refusal reason"
    (is (not (pr/blocked? (pr/success "env-1" "done"))))
    (is (not (pr/blocked? (pr/error "env-1" "boom" "boom" {}))))
    (is (nil? (pr/blocked-reason (pr/success "env-1" "done"))))))

(deftest outcome-resolves-the-typed-act-test
  (testing "a succeeding phase reports :success"
    (is (= :success (pr/outcome (pr/success "env-1" "done") true))))
  (testing "a non-succeeding phase reports :failure"
    (is (= :failure (pr/outcome (pr/error "env-1" "boom" "boom" {}) false))))
  (testing "a refusal dominates — :blocked regardless of the success verdict"
    (let [result (pr/blocked "env-1" "refused" :missing-input {})]
      (is (= :blocked (pr/outcome result false)))
      (is (= :blocked (pr/outcome result true)))))
  (testing "a redirect from a succeeding phase reports :redirected"
    (let [result (pr/request-redirect (pr/success "env-1" "done") :verify)]
      (is (= :redirected (pr/outcome result true)))))
  (testing "an outright failure dominates a pending redirect"
    (let [result (pr/request-redirect (pr/success "env-1" "done") :verify)]
      (is (= :failure (pr/outcome result false))))))

(deftest outcome-skipped-test
  (testing "a skipped phase reports :skipped"
    (is (= :skipped (pr/outcome (pr/skipped :already-implemented) true))))
  (testing "a skip nested under :result (event-builder shape) is detected"
    (is (= :skipped (pr/outcome {:status :completed
                                 :result (pr/skipped :already-implemented)}
                                true))))
  (testing "an outright failure still dominates a skip-shaped result"
    (is (= :failure (pr/outcome (pr/skipped :already-implemented) false)))))

(deftest skipped?-predicate-test
  (testing "true for a bare skip result and for one nested under :result"
    (is (pr/skipped? (pr/skipped :already-implemented)))
    (is (pr/skipped? {:result (pr/skipped :already-implemented)})))
  (testing "false for plain success/error results"
    (is (not (pr/skipped? (pr/success "env-1" "done"))))
    (is (not (pr/skipped? (pr/error "env-1" "boom" "boom" {}))))))
