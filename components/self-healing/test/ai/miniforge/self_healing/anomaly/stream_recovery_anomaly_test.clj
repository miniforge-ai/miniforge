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
   `response/throw-anomaly!`. Caller-supplied bad input →
   `:anomalies/incorrect`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.self-healing.stream-recovery :as recovery])
  (:import (clojure.lang ExceptionInfo)))

(deftest binary-for-nil-backend-throws-anomaly
  (testing "nil backend raises :anomalies/incorrect"
    (let [thrown (try (recovery/binary-for nil) nil (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"backend must not be nil" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))

(deftest binary-for-non-named-backend-throws-anomaly
  (testing "non-keyword non-symbol backend raises :anomalies/incorrect"
    (let [thrown (try (recovery/binary-for "string-backend")
                      nil
                      (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"keyword or symbol" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))

(deftest evaluate-stall-recovery-non-iatom-hang-count-throws-anomaly
  (testing "non-IAtom :hang-count raises :anomalies/incorrect"
    (let [thrown (try
                   (recovery/evaluate-stall-recovery
                    {:phase-id :impl
                     :backend :anthropic
                     :session-id "sid"
                     :hang-count 1
                     :config {}
                     :allowed-failover-backends []})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #":hang-count must be an IAtom" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))

(deftest evaluate-stall-recovery-nil-backend-throws-anomaly
  (testing "nil :backend raises :anomalies/incorrect"
    (let [thrown (try
                   (recovery/evaluate-stall-recovery
                    {:phase-id :impl
                     :backend nil
                     :session-id "sid"
                     :hang-count (atom 1)
                     :config {}
                     :allowed-failover-backends []})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #":backend is required" (.getMessage thrown)))
      (is (= :anomalies/incorrect (:anomaly/category (ex-data thrown)))))))
