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
(ns ai.miniforge.self-healing.anomaly.stream-recovery-anomaly-test
  "Coverage for `stream-recovery/binary-for` and
   `stream-recovery/evaluate-stall-recovery` boundary escalation via
   returned anomaly maps. Caller-supplied bad input → `:anomalies/incorrect`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.self-healing.stream-recovery :as recovery]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} binary-for-nil-backend-returns-anomaly
  (testing "nil backend returns :anomalies/incorrect"
    (let [result (#'recovery/binary-for nil)]
      (is (= :anomalies/incorrect (:anomaly/category result))))))

(deftest ^{:stratum 0} binary-for-non-named-backend-returns-anomaly
  (testing "non-keyword non-symbol backend returns :anomalies/incorrect"
    (let [result (#'recovery/binary-for "string-backend")]
      (is (= :anomalies/incorrect (:anomaly/category result))))))

(deftest ^{:stratum 0} evaluate-stall-recovery-non-iatom-hang-count-returns-anomaly
  (testing "non-IAtom :hang-count returns :anomalies/incorrect"
    (let [result (recovery/evaluate-stall-recovery
                  {:phase-id :impl
                   :backend :anthropic
                   :session-id "sid"
                   :hang-count 1
                   :config {}
                   :allowed-failover-backends []})]
      (is (= :anomalies/incorrect (:anomaly/category result))))))

(deftest ^{:stratum 0} evaluate-stall-recovery-nil-backend-returns-anomaly
  (testing "nil :backend returns :anomalies/incorrect"
    (let [result (recovery/evaluate-stall-recovery
                  {:phase-id :impl
                   :backend nil
                   :session-id "sid"
                   :hang-count (atom 1)
                   :config {}
                   :allowed-failover-backends []})]
      (is (= :anomalies/incorrect (:anomaly/category result))))))

(deftest ^{:stratum 0} evaluate-stall-recovery-invalid-backend-returns-anomaly
  (testing "non-coercible :backend returns :anomalies/incorrect"
    (let [result (recovery/evaluate-stall-recovery
                  {:phase-id :impl
                   :backend 42
                   :session-id "sid"
                   :hang-count (atom 1)
                   :config {}
                   :allowed-failover-backends []})]
      (is (= :anomalies/incorrect (:anomaly/category result))))))

(deftest ^{:stratum 0} execute-resume-invalid-backend-returns-anomaly
  (testing "invalid backend returns anomaly before ProcessBuilder startup"
    (let [result (recovery/execute-resume! 42 "sid")]
      (is (= :anomalies/incorrect (:anomaly/category result))))))
