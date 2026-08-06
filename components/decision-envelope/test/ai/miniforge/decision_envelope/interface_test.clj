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
(ns ai.miniforge.decision-envelope.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.decision-envelope.interface :as env]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pins {:pins/pack-revision "pack-r1"
           :pins/rule-ids [:rule/no-secrets]
           :pins/event-watermark 42})

(deftest ^{:stratum 0} derivation-table-test
  (testing "deny-class reasons force :deny"
    (doseq [code [:reason/rule-violation :reason/unknown-enforcement
                  :reason/unknown-severity :reason/missing-artifact
                  :reason/gate-check-failed :reason/grant-exceeded
                  :reason/grant-scope-mismatch :reason/grant-absent]]
      (is (= :deny (env/derive-decision [{:reason/code code :reason/detail "x"}] []))
          (str code))))
  (testing "approval obligation denies until a workflow clears it"
    (is (= :deny (env/derive-decision [] [{:obligation/type :obligation/approval-required}]))))
  (testing "warn/audit obligations allow-with-obligations"
    (is (= :allow-with-obligations
           (env/derive-decision [] [{:obligation/type :obligation/warn-recorded}])))
    (is (= :allow-with-obligations
           (env/derive-decision [] [{:obligation/type :obligation/audit-recorded}]))))
  (testing "no reasons, no obligations: allow"
    (is (= :allow (env/derive-decision [] [])))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} closed-schema-test
  (let [e (env/envelope [] [] pins)]
    (is (env/valid? e))
    (testing "unknown keys are rejected — contract, not convention"
      (is (not (env/valid? (assoc e :envelope/extra :x))))
      (is (not (m/validate env/DecisionEnvelope (assoc e :sneaky true)))))))

(deftest ^{:stratum 1} decision-is-derived-not-supplied-test
  (testing "clean inputs allow"
    (is (= :allow (:envelope/decision (env/envelope [] [] pins)))))
  (testing "a caller-supplied decision cannot enter the factory"
    (let [e (env/envelope [] [] pins)]
      (is (= :allow (:envelope/decision e)))
      (is (not (env/valid? (assoc e :envelope/decision :maybe))))))
  (testing "produced-by admits only :runtime"
    (let [e (env/envelope [] [] pins)]
      (is (= :runtime (:envelope/produced-by e)))
      (is (not (env/valid? (assoc e :envelope/produced-by :agent)))))))

(deftest ^{:stratum 1} validation-anomaly-test
  (testing "free-form reason codes are rejected at mint time"
    (let [r (env/envelope [{:reason/code :reason/vibes :reason/detail "nope"}] [] pins)]
      (is (anomaly/anomaly? r))))
  (testing "malformed pins are rejected"
    (is (anomaly/anomaly? (env/envelope [] [] {:pins/pack-revision 7})))))
