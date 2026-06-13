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

(ns ai.miniforge.agent-runtime.error-classifier.taxonomy-test
  "Tests for the canonical failure taxonomy — failure-classes set,
   FailureClass Malli schema, legacy-type->failure-class map, and
   resolve-failure-class function."
  (:require
   [clojure.test :refer [deftest is testing are]]
   [malli.core :as m]
   [ai.miniforge.agent-runtime.error-classifier.taxonomy :as taxonomy]))

;;------------------------------------------------------------------------------ Layer 0
;; failure-classes set membership

(deftest test-failure-classes-count
  (testing "failure-classes contains exactly 10 members"
    (is (= 10 (count taxonomy/failure-classes)))))

(deftest test-failure-classes-membership
  (testing "all expected class keywords are present"
    (are [kw] (contains? taxonomy/failure-classes kw)
      :failure.class/agent-backend
      :failure.class/task-code
      :failure.class/external-service
      :failure.class/rate-limit
      :failure.class/timeout
      :failure.class/auth
      :failure.class/resource-exhaustion
      :failure.class/configuration
      :failure.class/concurrency
      :failure.class/unknown)))

(deftest test-failure-classes-no-unknowns
  (testing "old unqualified type keywords are NOT in failure-classes"
    (are [kw] (not (contains? taxonomy/failure-classes kw))
      :agent-backend
      :task-code
      :external
      :backend-setup
      nil)))

;;------------------------------------------------------------------------------ Layer 1
;; FailureClass Malli schema validation

(deftest test-failure-class-schema-valid-members
  (testing "FailureClass schema accepts all 10 canonical members"
    (are [kw] (true? (m/validate taxonomy/FailureClass kw))
      :failure.class/agent-backend
      :failure.class/task-code
      :failure.class/external-service
      :failure.class/rate-limit
      :failure.class/timeout
      :failure.class/auth
      :failure.class/resource-exhaustion
      :failure.class/configuration
      :failure.class/concurrency
      :failure.class/unknown)))

(deftest test-failure-class-schema-rejects-non-members
  (testing "FailureClass schema rejects non-member values"
    (are [v] (false? (m/validate taxonomy/FailureClass v))
      :agent-backend
      :task-code
      :external
      :backend-setup
      :rate-limit
      nil
      "failure.class/rate-limit"
      42)))

;;------------------------------------------------------------------------------ Layer 1
;; legacy-type->failure-class map

(deftest test-legacy-type-mapping-all-keys
  (testing "legacy-type->failure-class covers all expected old type keywords"
    (is (= :failure.class/agent-backend
           (get taxonomy/legacy-type->failure-class :agent-backend)))
    (is (= :failure.class/task-code
           (get taxonomy/legacy-type->failure-class :task-code)))
    (is (= :failure.class/external-service
           (get taxonomy/legacy-type->failure-class :external)))
    (is (= :failure.class/configuration
           (get taxonomy/legacy-type->failure-class :backend-setup)))))

(deftest test-legacy-type-mapping-values-are-valid-classes
  (testing "every value in the legacy map is a valid failure class"
    (doseq [v (vals taxonomy/legacy-type->failure-class)]
      (is (contains? taxonomy/failure-classes v)
          (str v " must be a member of failure-classes")))))

;;------------------------------------------------------------------------------ Layer 2
;; resolve-failure-class — all branches

(deftest test-resolve-explicit-failure-class-wins
  (testing "explicit :failure/class in pattern takes priority over everything else"
    (is (= :failure.class/auth
           (taxonomy/resolve-failure-class
            {:failure/class :failure.class/auth :rate-limit? true :type :agent-backend}
            "any message")))
    (is (= :failure.class/timeout
           (taxonomy/resolve-failure-class
            {:failure/class :failure.class/timeout}
            "")))))

(deftest test-resolve-rate-limit-branch
  (testing ":rate-limit? true fires when no explicit :failure/class is present"
    (is (= :failure.class/rate-limit
           (taxonomy/resolve-failure-class
            {:rate-limit? true :type :external}
            "429 too many requests")))
    (is (= :failure.class/rate-limit
           (taxonomy/resolve-failure-class
            {:rate-limit? true}
            "")))))

(deftest test-resolve-legacy-type-mapping
  (testing "each legacy :type keyword maps to its canonical class"
    (are [legacy-type expected-class]
         (= expected-class
            (taxonomy/resolve-failure-class {:type legacy-type} "msg"))
      :agent-backend :failure.class/agent-backend
      :task-code     :failure.class/task-code
      :external      :failure.class/external-service
      :backend-setup :failure.class/configuration)))

(deftest test-resolve-nil-pattern-map-returns-unknown
  (testing "nil pattern-map returns :failure.class/unknown"
    (is (= :failure.class/unknown
           (taxonomy/resolve-failure-class nil "some error")))
    (is (= :failure.class/unknown
           (taxonomy/resolve-failure-class nil nil)))))

(deftest test-resolve-empty-map-returns-unknown
  (testing "empty pattern map returns :failure.class/unknown"
    (is (= :failure.class/unknown
           (taxonomy/resolve-failure-class {} "some error")))
    (is (= :failure.class/unknown
           (taxonomy/resolve-failure-class {} "")))))

(deftest test-resolve-invalid-explicit-class-falls-through
  (testing "a :failure/class value not in failure-classes is ignored and falls through"
    ;; Falls through to :rate-limit? branch
    (is (= :failure.class/rate-limit
           (taxonomy/resolve-failure-class
            {:failure/class :some/garbage :rate-limit? true}
            "msg")))
    ;; Falls through to :type branch
    (is (= :failure.class/external-service
           (taxonomy/resolve-failure-class
            {:failure/class :some/garbage :type :external}
            "msg")))
    ;; Falls through to unknown
    (is (= :failure.class/unknown
           (taxonomy/resolve-failure-class
            {:failure/class :some/garbage}
            "msg")))))

(deftest test-resolve-never-returns-nil
  (testing "resolve-failure-class never returns nil under any input"
    (are [pattern msg] (some? (taxonomy/resolve-failure-class pattern msg))
      nil nil
      nil ""
      {} ""
      {:type :unrecognised-legacy-type} "msg"
      {:failure/class nil} "msg"
      {:rate-limit? false} "msg")))

(deftest test-resolve-result-always-valid-class
  (testing "resolve-failure-class always returns a member of failure-classes"
    (are [pattern msg]
         (contains? taxonomy/failure-classes
                    (taxonomy/resolve-failure-class pattern msg))
      nil "err"
      {} "err"
      {:type :agent-backend} "err"
      {:type :task-code} "err"
      {:type :external} "err"
      {:type :backend-setup} "err"
      {:rate-limit? true} "err"
      {:failure/class :failure.class/concurrency} "err"
      {:failure/class :failure.class/resource-exhaustion} "err")))
