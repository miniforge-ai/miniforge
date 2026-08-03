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
(ns ai.miniforge.gate.codex-consultation-test
  (:require [ai.miniforge.gate.codex-consultation :as gate]
            [ai.miniforge.gate.registry :as registry]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} absent-capability-passes-clean
  (testing "no marker at all — codex feature never ran"
    (is (true? (:passed? (gate/check-codex-consultation {} {})))))
  (testing "unconfigured / unmapped — capability off, no nagging"
    (is (true? (:passed? (gate/check-codex-consultation
                           {:codex/consultation {:status :unconfigured}} {}))))
    (is (true? (:passed? (gate/check-codex-consultation
                           {:codex/consultation {:status :unmapped}} {}))))))

(deftest ^{:stratum 0} configured-but-skipped-fails-closed
  (let [r (gate/check-codex-consultation
            {:codex/consultation {:pinned? false :status :skipped
                                  :anomaly :codex-unreadable :pin-read? nil}} {})]
    (is (false? (:passed? r)))
    (is (= :codex-consultation-skipped (-> r :errors first :type)))
    (is (= :codex-unreadable (-> r :errors first :anomaly)))))

(deftest ^{:stratum 0} pinned-but-unfetched-warns-only
  (let [r (gate/check-codex-consultation
            {:codex/consultation {:pinned? true :status :pinned
                                  :anomaly nil :pin-read? false}} {})]
    (is (true? (:passed? r)))
    (is (= :codex-pin-not-fetched (-> r :warnings first :type)))))

(deftest ^{:stratum 0} pinned-and-read-passes-clean
  (let [r (gate/check-codex-consultation
            {:codex/consultation {:pinned? true :status :pinned
                                  :anomaly nil :pin-read? true}} {})]
    (is (true? (:passed? r)))
    (is (empty? (:warnings r)))))

(deftest ^{:stratum 0} unknown-read-state-does-not-warn
  ;; :pin-read? nil = the session surfaced no reads log (e.g. capsule mode);
  ;; unknown must not be treated as unread.
  (let [r (gate/check-codex-consultation
            {:codex/consultation {:pinned? true :status :pinned
                                  :anomaly nil :pin-read? nil}} {})]
    (is (true? (:passed? r)))
    (is (empty? (:warnings r)))))

(deftest ^{:stratum 0} registered-in-the-gate-registry
  (is (= :codex-consultation (:name (registry/get-gate :codex-consultation)))))
