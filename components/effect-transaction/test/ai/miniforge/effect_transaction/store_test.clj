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
  "Durability: the record must reach disk BEFORE the effect, and survive
   to a reader that shares no memory with the writer."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
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

(deftest ^{:stratum 0} propose-refuses-a-missing-store-dir-test
  ;; A durability component silently writing the audit trail into the
  ;; process CWD is the worst failure it could have — refuse, never
  ;; default.
  (let [result (fx/propose! {:effect-class :effect/merge
                             :grant-id (random-uuid)
                             :proposal {}})]
    (is (anomaly/anomaly? result))
    (is (= :invalid-input (:anomaly/type result)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} record-is-durable-before-the-effect-test
  ;; The acceptance criterion for a process kill between propose and
  ;; commit: the intent must already be on disk.
  (let [dir (tmp-dir)
        t (fx/propose! dir
                       {:effect-class :effect/merge
                        :grant-id (random-uuid)
                        :envelope-id (random-uuid)
                        :proposal {:pr/repo "miniforge-ai/miniforge" :pr/number 42}}
                       now)]
    (is (fx/valid? t))
    (is (= :proposed (:effect/state t)))
    (testing "a fresh reader finds it — nothing is held only in memory"
      (let [from-disk (fx/read-record dir (:effect/id t))]
        (is (= (:effect/id t) (:effect/id from-disk)))
        (is (= :proposed (:effect/state from-disk)))
        (is (= (:effect/proposal t) (:effect/proposal from-disk)))
        (is (= now (:effect/at from-disk)) "the instant round-trips through EDN")))
    (testing "the record is listed, and no .tmp partial ever is"
      (is (= 1 (count (fx/list-records dir)))))
    (testing "an absent id reads as nil, not a fabricated record"
      (is (nil? (fx/read-record dir (random-uuid)))))
    (testing "listing a directory that does not exist is empty, not an error"
      (is (= [] (fx/list-records (str dir "/nope")))))))
