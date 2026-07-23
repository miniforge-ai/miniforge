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

(ns ai.miniforge.gate.lint-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.gate.lint :as lint]
            [ai.miniforge.policy-pack.interface :as policy-pack]))

(deftest run-policy-pack-check-fails-closed-on-exception
  (testing "when check-artifact throws, run-policy-pack-check returns a :check-error failure"
    (with-redefs [policy-pack/check-artifact
                  (fn [_packs _artifact _context]
                    (throw (ex-info "simulated policy check failure" {:code :timeout})))]
      (let [result (lint/run-policy-pack-check {:content "code"}
                                               {:policy-packs [:standards]})]
        (is (false? (:passed? result))
            "gate must fail closed on exception, not pass")
        (is (seq (:errors result))
            "a failure result must carry at least one error")
        (is (= :check-error (-> result :errors first :type))
            "exception errors must use the :check-error type")))))

(deftest run-policy-pack-check-preserves-policy-pack-result-shape
  (testing "policy-pack errors and warnings keep their public message/severity keys"
    (with-redefs [policy-pack/check-artifact
                  (fn [_packs _artifact _context]
                    {:blocking [{:code :no-secrets
                                 :message "Found secret"
                                 :severity :critical}]
                     :warnings [{:code :no-todos
                                 :message "Found TODO"
                                 :severity :low}]})]
      (is (= {:passed? false
              :errors [{:type :policy-violation
                        :message "Found secret"
                        :severity :critical
                        :rule-id :no-secrets}]
              :warnings [{:type :policy-warning
                          :message "Found TODO"
                          :severity :low
                          :rule-id :no-todos}]}
             (lint/run-policy-pack-check {:content "SECRET TODO"}
                                         {:policy-packs [:standards]}))))))

(deftest check-lint-fails-closed-when-policy-check-returns-nil
  (testing "check-lint fails closed when packs are configured but run-policy-pack-check returns nil"
    (with-redefs [lint/run-policy-pack-check (fn [_artifact _ctx] nil)]
      (let [result (lint/check-lint {:content "code"} {:policy-packs [:standards]})]
        (is (false? (:passed? result))
            "nil pack result with packs configured must fail closed")
        (is (seq (:errors result))
            "a failed result must carry at least one error")
        (is (= :check-error (-> result :errors first :type))
            "nil-guard errors must use the :check-error type")
        (is (pos? (-> result :errors first :data :pack-count))
            "diagnostic data must include pack-count")))))
