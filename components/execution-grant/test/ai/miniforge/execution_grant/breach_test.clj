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
(ns ai.miniforge.execution-grant.breach-test
  (:require
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} later (Instant/parse "2026-08-01T01:00:00Z"))

(def ^{:stratum 0} much-later (Instant/parse "2026-08-02T00:00:00Z"))

(defn ^{:stratum 0} tmp-dir []
  (str (.toFile (Files/createTempDirectory "breach" (into-array FileAttribute [])))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} spend-grant
  ([] (spend-grant {}))
  ([overrides]
   (grant/issue (merge {:principal "agent:implementer"
                        :effect-class :effect/spend
                        :scope {}
                        :constraints {:constraint/max-cost-usd 5.0}
                        :delegable? true
                        :expires-at much-later}
                       overrides)
                now)))

(deftest ^{:stratum 1} malformed-breach-is-refused-test
  (let [dir (tmp-dir)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (grant/record-breach! dir {:breach/id (random-uuid)})))))

(deftest ^{:stratum 1} a-reused-breach-id-cannot-overwrite-history-test
  ;; Append-only must be enforced by the filesystem, not asserted in a
  ;; docstring. A reused id silently replacing an earlier breach is the
  ;; exact edit this record exists to make impossible.
  (let [dir (tmp-dir)
        b {:breach/id (random-uuid)
           :breach/principal "agent:x"
           :breach/grant-id (random-uuid)
           :breach/effect-class :effect/spend
           :breach/axis :constraint/max-cost-usd
           :breach/limit 5.0
           :breach/observed 9.0
           :breach/detection :detected
           :breach/at now}]
    (grant/record-breach! dir b)
    (is (thrown? clojure.lang.ExceptionInfo
                 (grant/record-breach! dir (assoc b :breach/observed 1.0))))
    (testing "the original survives untouched"
      (is (= 9.0 (:breach/observed (first (grant/breach-history dir "agent:x"))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} revoke-for-cause-ends-the-grant-and-remembers-test
  (let [dir (tmp-dir)
        g (spend-grant)
        revoked (grant/revoke-for-cause! dir g
                                         {:axis :constraint/max-cost-usd
                                          :limit 5.0 :observed 9.0
                                          :detection :detected}
                                         later)]
    (testing "the grant carries the cause"
      (is (grant/revoked? revoked))
      (is (= :breach/cost-exceeded (:grant/revocation-reason revoked)))
      (is (not (grant/active? revoked later))))
    (testing "the history carries the numbers"
      (let [[b :as all] (grant/breach-history dir "agent:implementer")]
        (is (= 1 (count all)))
        (is (= 5.0 (:breach/limit b)))
        (is (= 9.0 (:breach/observed b)))
        (is (= :constraint/max-cost-usd (:breach/axis b)))
        (is (= later (:breach/at b)))))))

(deftest ^{:stratum 2} prevented-and-detected-stay-distinguishable-test
  ;; A governance product that reports a breach it merely NOTICED as one
  ;; it PREVENTED is claiming a gate it does not have.
  (let [dir (tmp-dir)]
    (grant/revoke-for-cause! dir (spend-grant)
                             {:axis :constraint/max-cost-usd :limit 5.0
                              :observed 6.0 :detection :prevented} later)
    (grant/revoke-for-cause! dir (spend-grant {:principal "agent:other"})
                             {:axis :constraint/max-cost-usd :limit 5.0
                              :observed 7.0 :detection :detected} later)
    (let [all (grant/breach-history dir)]
      (is (= #{:prevented :detected} (set (map :breach/detection all)))))))

(deftest ^{:stratum 2} history-is-append-only-and-per-principal-test
  (let [dir (tmp-dir)]
    (dotimes [_ 3]
      (grant/revoke-for-cause! dir (spend-grant)
                               {:axis :constraint/max-cost-usd :limit 5.0
                                :observed 6.0 :detection :detected} later))
    (grant/revoke-for-cause! dir (spend-grant {:principal "agent:other"})
                             {:axis :constraint/max-cost-usd :limit 5.0
                              :observed 6.0 :detection :detected} later)
    (testing "every breach is kept — later ones never overwrite earlier"
      (is (= 4 (count (grant/breach-history dir)))))
    (testing "history narrows by principal"
      (is (= 3 (count (grant/breach-history dir "agent:implementer"))))
      (is (= 1 (count (grant/breach-history dir "agent:other")))))
    (testing "a principal with no history reads empty, not an error"
      (is (= [] (grant/breach-history dir "agent:never-breached")))
      (is (= [] (grant/breach-history (str dir "/nope")))))))

(deftest ^{:stratum 2} a-breacher-cannot-immediately-re-obtain-the-ceiling-test
  (let [dir (tmp-dir)
        p "agent:implementer"]
    (testing "with no history, anything is eligible"
      (is (grant/eligible? dir p :effect/spend {:constraint/max-cost-usd 100.0})))
    (grant/revoke-for-cause! dir (spend-grant)
                             {:axis :constraint/max-cost-usd :limit 5.0
                              :observed 9.0 :detection :detected} later)
    (testing "the breached ceiling is refused, and so is anything above it"
      (is (not (grant/eligible? dir p :effect/spend {:constraint/max-cost-usd 5.0})))
      (is (not (grant/eligible? dir p :effect/spend {:constraint/max-cost-usd 50.0}))))
    (testing "a strictly smaller ceiling is still available — narrower, not banned"
      (is (grant/eligible? dir p :effect/spend {:constraint/max-cost-usd 1.0})))
    (testing "asking for NO limit after breaching one is the widest request, not an abstention"
      (is (not (grant/eligible? dir p :effect/spend {}))))
    (testing "the cap is per effect class"
      (is (grant/eligible? dir p :effect/merge {:constraint/max-cost-usd 50.0})))
    (testing "and per principal"
      (is (grant/eligible? dir "agent:clean" :effect/spend {:constraint/max-cost-usd 50.0})))
    (is (= 5.0 (grant/permitted-ceiling dir p :effect/spend :constraint/max-cost-usd)))))
