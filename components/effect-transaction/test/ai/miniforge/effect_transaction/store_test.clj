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
(ns ai.miniforge.effect-transaction.store-test
  "Durability: a record must survive to a reader that shares no memory
   with the writer. That is what makes 'the record exists before the
   effect' a claim rather than a hope."
  (:require
   [ai.miniforge.effect-transaction.interface :as fx]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(defn ^{:stratum 0} tmp-dir []
  (str (.toFile (Files/createTempDirectory "fx-store-test" (into-array FileAttribute [])))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record
  ([] (record {}))
  ([overrides]
   (merge {:effect/id (random-uuid)
           :effect/class :effect/merge
           :effect/grant-id (random-uuid)
           :effect/proposal {:pr/repo "miniforge-ai/miniforge" :pr/number 42}
           :effect/state :proposed
           :effect/at now
           :effect/updated-at now}
          overrides)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} schema-is-closed-test
  (is (fx/valid? (record)))
  (testing "unknown keys are rejected — a record nobody can describe is one nobody can reconcile"
    (is (not (fx/valid? (record {:effect/surprise :x})))))
  (testing "an unknown state is rejected"
    (is (not (fx/valid? (record {:effect/state :vibes})))))
  (testing ":unknown-outcome and :committing are real states, not error codes"
    (is (fx/valid? (record {:effect/state :unknown-outcome})))
    (is (fx/valid? (record {:effect/state :committing})))
    (is (contains? fx/reconcilable-states :unknown-outcome))
    (is (contains? fx/reconcilable-states :committing))
    (is (not (contains? fx/terminal-states :unknown-outcome))
        "unknown is not settled — something must still ask")))

(deftest ^{:stratum 2} record-survives-to-a-fresh-reader-test
  (let [dir (tmp-dir)
        t (record)]
    (fx/write! dir t)
    (testing "a reader sharing no memory with the writer finds it whole"
      (let [from-disk (fx/read-record dir (:effect/id t))]
        (is (= (:effect/id t) (:effect/id from-disk)))
        (is (= :proposed (:effect/state from-disk)))
        (is (= (:effect/proposal t) (:effect/proposal from-disk)))
        (is (= now (:effect/at from-disk))
            "the instant round-trips — EDN has no Instant reader, so it crosses as ISO-8601")))
    (testing "an absent id reads as nil, not a fabricated record"
      (is (nil? (fx/read-record dir (random-uuid)))))))

(deftest ^{:stratum 2} listing-skips-partials-and-missing-dirs-test
  (let [dir (tmp-dir)]
    (dotimes [_ 3] (fx/write! dir (record)))
    (is (= 3 (count (fx/list-records dir))))
    (testing "a .tmp partial is never mistaken for a record"
      (spit (str dir "/half-written.edn.tmp") "{:effect/id ")
      (is (= 3 (count (fx/list-records dir)))))
    (testing "listing a directory that does not exist is empty, not an error"
      (is (= [] (fx/list-records (str dir "/nope")))))))

(deftest ^{:stratum 2} rewrite-replaces-in-place-test
  ;; State advances rewrite the same record; the store must replace
  ;; rather than accumulate, or a reader could find two truths.
  (let [dir (tmp-dir)
        t (record)]
    (fx/write! dir t)
    (fx/write! dir (assoc t :effect/state :committing))
    (is (= 1 (count (fx/list-records dir))))
    (is (= :committing (:effect/state (fx/read-record dir (:effect/id t)))))))
