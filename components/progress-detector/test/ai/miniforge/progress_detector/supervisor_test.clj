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

(ns ai.miniforge.progress-detector.supervisor-test
  "Tests for the Stage 2 minimal supervisor."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.supervisor :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Anomaly fixtures — minimal shape needed to exercise the supervisor

(defn- mk-anomaly
  "Construct a minimal anomaly map under :anomaly/data — the supervisor
   only reads :anomaly/class, :anomaly/severity, and the
   :anomaly/evidence :event-ids vector for tie-breaking."
  ([anomaly-class severity]
   (mk-anomaly anomaly-class severity nil))
  ([anomaly-class severity event-seq]
   {:anomaly/type :fault
    :anomaly/data
    {:detector/kind    :detector/test
     :anomaly/class    anomaly-class
     :anomaly/severity severity
     :anomaly/evidence {:summary    (str (name anomaly-class) " " (name severity))
                        :event-ids  (cond-> [] event-seq (conj event-seq))}}}))

;------------------------------------------------------------------------------ Layer 1
;; Empty + class-default policy

(deftest empty-anomalies-continue-test
  (testing "no anomalies ⇒ :continue, no controlling anomaly"
    (let [decision (sut/handle [])]
      (is (= :continue (:action decision)))
      (is (= [] (:anomalies decision)))
      (is (nil? (:anomaly decision))))))

(deftest mechanical-error-terminates-test
  (testing "single mechanical :error ⇒ :terminate"
    (let [a        (mk-anomaly :mechanical :error 1)
          decision (sut/handle [a])]
      (is (= :terminate (:action decision)))
      (is (= a (:anomaly decision)))
      (is (string? (:reason decision)))
      (is (sut/terminate? decision)))))

(deftest heuristic-warn-does-not-terminate-test
  (testing "single heuristic :warn ⇒ :warn (logged, not terminated)"
    (let [a        (mk-anomaly :heuristic :warn 1)
          decision (sut/handle [a])]
      (is (= :warn (:action decision)))
      (is (false? (sut/terminate? decision)))
      (is (= a (:anomaly decision))))))

(deftest fatal-mechanical-terminates-test
  (testing "fatal severity terminates regardless"
    (let [a        (mk-anomaly :mechanical :fatal 1)
          decision (sut/handle [a])]
      (is (= :terminate (:action decision))))))

(deftest custom-policy-overrides-default-test
  (testing "caller-supplied policy wins over default"
    (let [permissive {:mechanical :warn :heuristic :warn}
          a          (mk-anomaly :mechanical :error 1)
          decision   (sut/handle permissive [a])]
      (is (= :warn (:action decision))
          "custom policy treats mechanical anomalies as warn"))))

;------------------------------------------------------------------------------ Layer 2
;; Controlling-anomaly selection

(deftest severity-rank-fatal-beats-error-test
  (testing ":fatal > :error > :warn > :info"
    (let [info  (mk-anomaly :mechanical :info  1)
          warn  (mk-anomaly :mechanical :warn  2)
          err   (mk-anomaly :mechanical :error 3)
          fatal (mk-anomaly :mechanical :fatal 4)]
      (is (= fatal (sut/select-controlling [info warn err fatal])))
      (is (= err   (sut/select-controlling [info warn err])))
      (is (= warn  (sut/select-controlling [info warn]))))))

(deftest class-rank-mechanical-beats-heuristic-test
  (testing "ties on severity broken by :mechanical > :heuristic"
    (let [heur (mk-anomaly :heuristic  :error 1)
          mech (mk-anomaly :mechanical :error 2)]
      (is (= mech (sut/select-controlling [heur mech])))
      (is (= mech (sut/select-controlling [mech heur]))))))

(deftest seq-tiebreak-earliest-wins-test
  (testing "ties on severity AND class broken by earliest :event/seq"
    (let [later   (mk-anomaly :mechanical :error 50)
          earlier (mk-anomaly :mechanical :error 10)]
      (is (= earlier (sut/select-controlling [later earlier])))
      (is (= earlier (sut/select-controlling [earlier later]))))))

(deftest seq-tiebreak-missing-loses-test
  (testing "anomalies with event-ids beat anomalies without"
    (let [no-seq    (mk-anomaly :mechanical :error nil)
          with-seq  (mk-anomaly :mechanical :error 99)]
      (is (= with-seq (sut/select-controlling [no-seq with-seq]))
          "missing :event-ids treated as MAX_VALUE per design"))))

;------------------------------------------------------------------------------ Layer 2
;; Decision map shape

(deftest decision-includes-all-anomalies-test
  (testing "decision :anomalies contains every input anomaly, not just controlling"
    (let [a1       (mk-anomaly :mechanical :error 1)
          a2       (mk-anomaly :heuristic  :warn  2)
          a3       (mk-anomaly :heuristic  :info  3)
          decision (sut/handle [a1 a2 a3])]
      (is (= 3 (count (:anomalies decision)))
          "all anomalies surfaced; supervisor only chooses controlling")
      (is (= a1 (:anomaly decision))
          "controlling is the strongest signal"))))

(deftest reason-only-on-terminate-test
  (testing ":reason key is present only when :action is :terminate"
    (let [terminate-decision (sut/handle [(mk-anomaly :mechanical :error 1)])
          warn-decision      (sut/handle [(mk-anomaly :heuristic :warn 1)])
          continue-decision  (sut/handle [])]
      (is (contains? terminate-decision :reason))
      (is (not (contains? warn-decision :reason)))
      (is (not (contains? continue-decision :reason))))))
