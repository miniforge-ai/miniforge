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
(ns ai.miniforge.effect-transaction.reconcile-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.fixtures :as fixture]
   [ai.miniforge.effect-transaction.interface :as fx]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now fixture/now)

(def ^{:stratum 0} later fixture/later)

(def ^{:stratum 0} tmp-dir fixture/tmp-dir)

(def ^{:stratum 0} merge-case! fixture/merge-case!)

(def ^{:stratum 0} succeed! fixture/succeed!)

(def ^{:stratum 0} no-usage fixture/no-usage)

(def ^{:stratum 0} probe-answer fixture/probe-answer)

(def ^{:stratum 0} throw-exception! fixture/throw-exception!)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} unknown-case!
  [dir]
  (let [{g :grant t :transaction} (merge-case! dir)]
    {:transaction t
     :unknown (fx/commit! dir t g no-usage now
                          (partial throw-exception! "boom"))}))

(deftest ^{:stratum 1} durable-settlement-overrides-stale-candidate-test
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)
        done (fx/commit! dir t g no-usage now succeed!)
        stale (assoc done :effect/state :unknown-outcome)
        probes (atom 0)
        result (fx/reconcile! dir stale (fn [_] (swap! probes inc)) later)]
    (is (= :conflict (:anomaly/type result)))
    (is (zero? @probes))))

(deftest ^{:stratum 1} missing-durable-record-does-not-probe-test
  (let [dir (tmp-dir)
        probes (atom 0)
        result (fx/reconcile! dir {:effect/id (random-uuid)}
                              (fn [_] (swap! probes inc)) later)]
    (is (= :conflict (:anomaly/type result)))
    (is (zero? @probes))))

(deftest ^{:stratum 1} invalid-candidate-id-does-not-probe-test
  (let [probes (atom 0)
        result (fx/reconcile! (tmp-dir) {:effect/id "../outside-store"}
                              (fn [_] (swap! probes inc)) later)]
    (is (= :invalid-input (:anomaly/type result)))
    (is (zero? @probes))))

(deftest ^{:stratum 1} committing-is-reconcilable-after-a-restart-test
  ;; A process that died mid-effect leaves the record at :committing.
  ;; That is not failure and not success — it is exactly the case
  ;; reconciliation exists for.
  (let [dir (tmp-dir)
        {g :grant t :transaction} (merge-case! dir)]
    (is (thrown? AssertionError
                 (fx/commit! dir t g no-usage now
                             (fn [] (throw (AssertionError.))))))
    (let [crashed (fx/read-record dir (:effect/id t))
          settled (fx/reconcile! dir crashed
                                 (fn [_] (probe-answer {:pr/state "OPEN"} false))
                                 later)]
      (is (contains? fx/reconcilable-states :committing))
      (is (= :reconciled (:effect/state settled)))
      (is (false? (:effect/matched? settled))))))

(deftest ^{:stratum 1} settled-record-reconciliation-is-conflict-test
  ;; A settled lifecycle position conflicts with the request; it is not an
  ;; authorization failure.
  (let [dir (tmp-dir)
        {settled-grant :grant settled-t :transaction} (merge-case! dir)
        settled (fx/commit! dir settled-t settled-grant no-usage now succeed!)
        result (fx/reconcile! dir settled
                              (constantly (probe-answer :unused true))
                              later)]
    (is (= :conflict (:anomaly/type result)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} reconciliation-records-probe-match-test
  (let [dir (tmp-dir)
        {unknown :unknown} (unknown-case! dir)
        settled (fx/reconcile! dir unknown
                               (fn [_] (probe-answer {:pr/state "MERGED"
                                                      :merge/sha "real-sha"}
                                                     true))
                               later)]
    (is (= :reconciled (:effect/state settled)))
    (is (= "real-sha" (get-in settled [:effect/observed :merge/sha])))
    (is (true? (:effect/matched? settled)))))

(deftest ^{:stratum 2} reconciliation-records-probe-mismatch-test
  (let [dir (tmp-dir)
        {unknown :unknown} (unknown-case! dir)
        settled (fx/reconcile! dir unknown
                               (fn [_] (probe-answer {:pr/state "CLOSED"} false))
                               later)]
    (is (= :reconciled (:effect/state settled)))
    (is (false? (:effect/matched? settled))
        "finding out includes finding out the proposal mismatched")))

(deftest ^{:stratum 2} throwing-probe-leaves-record-unresolved-test
  (let [dir (tmp-dir)
        {t :transaction unknown :unknown} (unknown-case! dir)
        result (fx/reconcile! dir unknown
                              (fn [_] (throw-exception! "network"))
                              later)]
    (is (anomaly/anomaly? result))
    (is (= :unknown-outcome (:effect/state (fx/read-record dir (:effect/id t))))
        "without an answer, reconciliation must not claim knowledge")))

(deftest ^{:stratum 2} unreadable-probe-answer-leaves-record-unresolved-test
  (let [dir (tmp-dir)
        {t :transaction unknown :unknown} (unknown-case! dir)
        result (fx/reconcile! dir unknown (constantly {:something :else}) later)]
    (is (anomaly/anomaly? result))
    (is (= :unknown-outcome (:effect/state (fx/read-record dir (:effect/id t)))))))

(deftest ^{:stratum 2} probe-answer-may-carry-in-band-marker-test
  ;; Probe data is caller-shaped, so internal status must never use an in-band
  ;; sentinel that can collide with a legitimate answer.
  (let [dir (tmp-dir)
        {unknown :unknown} (unknown-case! dir)
        answer (assoc (probe-answer {:threw "not an error"} true)
                      :threw "nor is this")
        settled (fx/reconcile! dir unknown (constantly answer) later)]
    (is (= :reconciled (:effect/state settled)))
    (is (true? (:effect/matched? settled)))))

(deftest ^{:stratum 2} absent-match-flag-records-mismatch-test
  (let [dir (tmp-dir)
        {unknown :unknown} (unknown-case! dir)
        settled (fx/reconcile! dir unknown
                               (constantly {:effect/observed {:pr/state "MERGED"}})
                               later)]
    (is (= :reconciled (:effect/state settled)))
    (is (false? (:effect/matched? settled))
        "an unflagged disagreement is not evidence of a match")))

(deftest ^{:stratum 2} unanswered-probe-is-unavailable-test
  ;; A silent external system is transient; callers must be able to retry.
  (let [dir (tmp-dir)
        {unknown :unknown} (unknown-case! dir)
        result (fx/reconcile! dir unknown
                              (fn [_] (throw-exception! "network down"))
                              later)]
    (is (= :unavailable (:anomaly/type result)))
    (is (= "network down" (get-in result [:anomaly/data :probe/error])))))
