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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.effect-transaction.persistence :as persistence]
   [ai.miniforge.effect-transaction.record :as effect-record]
   [ai.miniforge.effect-transaction.store :as store]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(defn ^{:stratum 0} tmp-dir []
  (str (.toFile (Files/createTempDirectory "fx-store-test" (into-array FileAttribute [])))))

(deftest ^{:stratum 0} directory-listing-failure-is-returned-as-data-test
  (let [denied (proxy [java.io.File] ["denied"]
                 (isDirectory [] true)
                 (listFiles [] (throw (SecurityException. "denied"))))]
    (with-redefs [io/file (constantly denied)]
      (is (anomaly/anomaly? (persistence/list-records "denied"))))))

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

(deftest ^{:stratum 1} corrupt-record-is-returned-as-data-test
  (let [dir (tmp-dir)
        id (random-uuid)]
    (spit (store/record-file dir id) "{:effect/id" :encoding "UTF-8")
    (is (anomaly/anomaly? (fx/read-record dir id)))))

(deftest ^{:stratum 1} lock-does-not-reclassify-application-errors-test
  (let [dir (tmp-dir)
        id (random-uuid)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (persistence/with-record-lock
                  dir id #(throw (ex-info "application failure" {})))))
    (is (= :released
           (persistence/with-record-lock dir id (constantly :released))))))

(deftest ^{:stratum 1} proposal-normalizes-schema-valid-date-test
  (let [dir (tmp-dir)
        id (random-uuid)
        t (fx/propose! dir {:effect-id id
                            :effect-class :effect/merge
                            :grant-id (random-uuid)
                            :envelope-id (random-uuid)}
                       (Date/from now))
        committing (assoc t :effect/state :committing)]
    (is (= id (:effect/id t)))
    (is (instance? Instant (:effect/at t)))
    (is (= committing (store/transition! dir t committing)))))

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
    (store/create! dir t)
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
    (dotimes [_ 3] (store/create! dir (record)))
    (is (= 3 (count (fx/list-records dir))))
    (testing "a .tmp partial is never mistaken for a record"
      (spit (str dir "/half-written.edn.tmp") "{:effect/id ")
      (is (= 3 (count (fx/list-records dir)))))
    (testing "listing a directory that does not exist is empty, not an error"
      (is (= [] (fx/list-records (str dir "/nope")))))))

(deftest ^{:stratum 2} compare-and-set-replaces-the-exact-record-test
  (let [dir (tmp-dir)
        t (record)
        committing (assoc t :effect/state :committing)]
    (store/create! dir t)
    (is (= committing (store/transition! dir t committing)))
    (is (= 1 (count (fx/list-records dir))))
    (is (= :committing (:effect/state (fx/read-record dir (:effect/id t)))))
    (testing "a stale expected value cannot replace the durable record"
      (let [result (store/transition! dir t (assoc t :effect/state :succeeded))]
        (is (anomaly/anomaly? result))
        (is (= :committing (:effect/state (fx/read-record dir (:effect/id t)))))))
    (testing "a missing durable value is reported as not found"
      (let [missing (record)
            result (store/transition! dir missing
                                      (assoc missing :effect/state :committing))]
        (is (= :anomalies.effect-transaction/not-found
               (:anomaly/subtype result)))))
    (testing "the store itself refuses to write under a replacement identity"
      (let [other-id (random-uuid)
            result (store/transition! dir committing
                                      (assoc committing :effect/id other-id))]
        (is (= :anomalies.effect-transaction/wrong-state
               (:anomaly/subtype result)))
        (is (= committing (fx/read-record dir (:effect/id t))))
        (is (nil? (fx/read-record dir other-id)))))
    (testing "a lifecycle change cannot replace the durable identity"
      (let [other-id (random-uuid)
            result (effect-record/advance! dir committing
                                           {:effect/id other-id}
                                           now)]
        (is (anomaly/anomaly? result))
        (is (= committing (fx/read-record dir (:effect/id t))))
        (is (nil? (fx/read-record dir other-id)))))))

(deftest ^{:stratum 2} both-inst-types-round-trip-test
  ;; The schema says `inst?`, and `inst?` admits java.util.Date as well
  ;; as java.time.Instant — so a record the schema ACCEPTS can arrive at
  ;; the store holding either. Both must survive the disk boundary, or
  ;; the store rejects records its own contract calls valid.
  (let [dir (tmp-dir)
        as-date (record {:effect/at (Date/from now)})
        as-instant (record {:effect/at now})]
    (is (fx/valid? as-date) "a Date is a valid :effect/at per the schema")
    (store/create! dir as-date)
    (store/create! dir as-instant)
    (is (= now (:effect/at (fx/read-record dir (:effect/id as-date))))
        "a Date normalizes to the same instant on the way out")
    (is (= now (:effect/at (fx/read-record dir (:effect/id as-instant)))))))

(deftest ^{:stratum 2} duplicate-id-does-not-replace-the-first-record-test
  (let [dir (tmp-dir)
        original (record)
        replacement (assoc original :effect/proposal {:pr/number 99})]
    (is (= original (store/create! dir original)))
    (is (anomaly/anomaly? (store/create! dir replacement)))
    (is (= original (fx/read-record dir (:effect/id original))))))
