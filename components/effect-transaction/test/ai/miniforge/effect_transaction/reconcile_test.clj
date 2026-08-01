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
   [ai.miniforge.effect-transaction.interface :as fx]
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

(defn ^{:stratum 0} tmp-dir
  "A fresh directory per test — records are files, so tests that shared
   one would see each other's."
  []
  (str (.toFile (Files/createTempDirectory "fx-test" (into-array FileAttribute [])))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} merge-grant
  ([] (merge-grant {}))
  ([overrides]
   (grant/issue (merge {:principal "agent:implementer"
                        :effect-class :effect/merge
                        :scope {:repo "miniforge-ai/miniforge" :pr 42}
                        :constraints {:constraint/max-count 5}
                        :delegable? false
                        :expires-at later}
                       overrides)
                now)))

(defn ^{:stratum 1} propose-merge!
  [dir g]
  (fx/propose! dir
               {:effect-class :effect/merge
                :grant-id (:grant/id g)
                :envelope-id (random-uuid)
                :proposal {:pr/repo "miniforge-ai/miniforge" :pr/number 42}}
               now))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} reconcile-reads-the-world-not-the-log-test
  (let [dir (tmp-dir)
        g (merge-grant)]
    (testing "the observation comes from the probe, and a match is recorded"
      (let [t (propose-merge! dir g)
            unknown (fx/commit! dir t g {} now (fn [] (throw (ex-info "boom" {}))))
            settled (fx/reconcile! dir unknown
                                   (fn [_] {:effect/observed {:pr/state "MERGED"
                                                              :merge/sha "real-sha"}
                                            :effect/matched? true})
                                   later)]
        (is (= :reconciled (:effect/state settled)))
        (is (= "real-sha" (get-in settled [:effect/observed :merge/sha])))
        (is (true? (:effect/matched? settled)))))

    (testing "a MISMATCH is recorded, not smoothed over"
      (let [t (propose-merge! dir g)
            unknown (fx/commit! dir t g {} now (fn [] (throw (ex-info "boom" {}))))
            settled (fx/reconcile! dir unknown
                                   (fn [_] {:effect/observed {:pr/state "CLOSED"}
                                            :effect/matched? false})
                                   later)]
        (is (= :reconciled (:effect/state settled)))
        (is (false? (:effect/matched? settled))
            "finding out includes finding out you were wrong")))

    (testing "a probe that throws leaves the record unresolved for a later attempt"
      (let [t (propose-merge! dir g)
            unknown (fx/commit! dir t g {} now (fn [] (throw (ex-info "boom" {}))))
            result (fx/reconcile! dir unknown (fn [_] (throw (ex-info "network" {}))) later)]
        (is (anomaly/anomaly? result))
        (is (= :unknown-outcome (:effect/state (fx/read-record dir (:effect/id t))))
            "marking :reconciled here would assert we found out when we did not")))

    (testing "a probe answering in an unreadable shape also leaves it unresolved"
      (let [t (propose-merge! dir g)
            unknown (fx/commit! dir t g {} now (fn [] (throw (ex-info "boom" {}))))
            result (fx/reconcile! dir unknown (constantly {:something :else}) later)]
        (is (anomaly/anomaly? result))
        (is (= :unknown-outcome (:effect/state (fx/read-record dir (:effect/id t)))))))

    (testing "a probe answer carrying the internal marker key is still honoured"
      ;; The probe returns caller-shaped data, so any in-band sentinel is
      ;; a value it could legitimately carry. Wrapping the answer instead
      ;; of probing it for a marker is what keeps that from colliding.
      (let [t (propose-merge! dir g)
            unknown (fx/commit! dir t g {} now (fn [] (throw (ex-info "boom" {}))))
            settled (fx/reconcile! dir unknown
                                   (constantly {:effect/observed {:threw "not an error"}
                                                :threw "nor is this"
                                                :effect/matched? true})
                                   later)]
        (is (= :reconciled (:effect/state settled)))
        (is (true? (:effect/matched? settled)))))

    (testing "an answer with no :effect/matched? records a mismatch, not a match"
      (let [t (propose-merge! dir g)
            unknown (fx/commit! dir t g {} now (fn [] (throw (ex-info "boom" {}))))
            settled (fx/reconcile! dir unknown
                                   (constantly {:effect/observed {:pr/state "MERGED"}})
                                   later)]
        (is (= :reconciled (:effect/state settled)))
        (is (false? (:effect/matched? settled))
            "an unflagged disagreement is worse than a flagged one")))

    (testing "an already-settled record is not reconcilable"
      (let [t (propose-merge! dir g)
            done (fx/commit! dir t g {} now (fn [] {:effect/outcome :succeeded}))]
        (is (anomaly/anomaly?
             (fx/reconcile! dir done (constantly {:effect/observed :x}) later)))))))

(deftest ^{:stratum 2} committing-is-reconcilable-after-a-restart-test
  ;; A process that died mid-effect leaves the record at :committing.
  ;; That is not failure and not success — it is exactly the case
  ;; reconciliation exists for.
  (let [dir (tmp-dir)
        g (merge-grant)
        t (propose-merge! dir g)
        ;; simulate the crash: the record reached :committing and nothing
        ;; ever wrote an outcome
        crashed (assoc t :effect/state :committing)
        settled (fx/reconcile! dir crashed
                               (fn [_] {:effect/observed {:pr/state "OPEN"}
                                        :effect/matched? false})
                               later)]
    (is (contains? fx/reconcilable-states :committing))
    (is (= :reconciled (:effect/state settled)))
    (is (false? (:effect/matched? settled)))))

(deftest ^{:stratum 2} refusal-anomalies-route-correctly-test
  ;; :unauthorized would say "you lack permission", which is neither
  ;; true nor useful here. A wrong lifecycle position is a :conflict; a
  ;; probe that did not answer is :unavailable — transient, ask again.
  (let [dir (tmp-dir)
        g (merge-grant)
        settled (fx/commit! dir (propose-merge! dir g) g {} now
                            (fn [] {:effect/outcome :succeeded}))
        unknown (fx/commit! dir (propose-merge! dir g) g {} now
                            (fn [] (throw (ex-info "boom" {}))))]
    (testing "reconciling a settled record is a :conflict, not a permission error"
      (is (= :conflict (:anomaly/type
                        (fx/reconcile! dir settled (constantly {:effect/observed :x}) later)))))
    (testing "a probe that did not answer is :unavailable, and carries the cause"
      (let [a (fx/reconcile! dir unknown (fn [_] (throw (ex-info "network down" {}))) later)]
        (is (= :unavailable (:anomaly/type a)))
        (is (= "network down" (get-in a [:anomaly/data :probe/error])))))))
