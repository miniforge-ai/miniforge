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
(ns ai.miniforge.agent-environment.file-ledger-test
  (:require
   [ai.miniforge.agent-environment.conformance.fixtures :as fx]
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-environment.interface.ledger :as store]
   [ai.miniforge.agent-environment.ledger :as ledger]
   [ai.miniforge.agent-environment.ledger.disk :as disk]
   [ai.miniforge.agent-environment.ledger-faults :as faults]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]
   [java.util Date]
   [java.util.concurrent CyclicBarrier TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} k
  [#uuid "00000000-0000-0000-0000-00000000000a"
   #uuid "00000000-0000-0000-0000-00000000000b"
   #uuid "00000000-0000-0000-0000-00000000000c"])

(def ^{:stratum 0} expires-at
  "Nanosecond precision, so a codec that drops any of it fails the round trip."
  (Instant/parse "2026-11-14T00:00:00.123456789Z"))

(def ^{:stratum 0} writers
  "Stores racing over one directory."
  2)

(def ^{:stratum 0} barrier-timeout-ms
  "How long a racing store waits for the other to read: generous, since
   both start at once, and bounded so a broken race fails rather than hangs."
  5000)

(def ^{:stratum 0} unavailable :anomalies.agent-environment/ledger-unavailable)

(defn- ^{:stratum 0} temp-dir
  []
  (str (.toFile (Files/createTempDirectory "ledger-test" (into-array FileAttribute [])))))

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(defn- ^{:stratum 0} bump [existing] (update existing :count (fnil inc 0)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} entry
  {:entry/state :state/provisioning
   :entry/generation 1
   :entry/request {:environment/expires-at expires-at :legacy/at (Date. 0)}})

(defn- ^{:stratum 1} meeting-first-read
  "`inner` whose first read waits at `barrier`, so racing stores both read
   the same revision before either writes."
  [inner ^CyclicBarrier barrier]
  (let [waited? (atom false)]
    (reify store/LedgerStore
      (read-entry [_ key]
        (when (compare-and-set! waited? false true)
          (.await barrier barrier-timeout-ms TimeUnit/MILLISECONDS))
        (store/read-entry inner key))
      (write-entry! [_ key revision changed] (store/write-entry! inner key revision changed))
      (entries [_] (store/entries inner)))))

(deftest ^{:stratum 1} test-a-restarted-controller-keeps-its-leases
  (let [dir (temp-dir)
        w (fx/world (env/fake-provider) :isolation/shared-kernel (env/file-ledger dir))
        req (fx/request w (:a w))
        acquired (fx/acquire w (:a w) req)
        current (:environment/lease acquired)
        restarted (faults/controller-over w (env/file-ledger dir))]
    (is (= {:readiness/ready? true} (env/readiness restarted (:a w) current)))
    (is (= acquired (env/acquire! restarted (:a w) (fx/provision-grant w (:a w)) req)))
    (is (= {:call/outcome :outcome/applied} (env/release! restarted (:a w) current)))
    (is (= :anomalies.agent-environment/environment-released (reason (env/readiness (:c w) (:a w) current))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-a-fresh-store-reads-what-an-earlier-one-wrote
  (let [dir (temp-dir)]
    (is (= {:ledger/revision 1} (store/write-entry! (env/file-ledger dir) k nil entry)))
    (let [restarted (env/file-ledger dir)]
      (is (= {:ledger/entry entry :ledger/revision 1} (store/read-entry restarted k)))
      (is (= [{:ledger/key k :ledger/entry entry :ledger/revision 1}] (store/entries restarted))))))

(deftest ^{:stratum 2} test-a-write-naming-a-stale-revision-is-a-conflict
  (let [s (env/file-ledger (temp-dir))]
    (store/write-entry! s k nil entry)
    (is (= :anomalies.agent-environment/ledger-conflict (reason (store/write-entry! s k nil entry))))
    (is (= {:ledger/revision 2} (store/write-entry! s k 1 entry)))))

(deftest ^{:stratum 2} test-racing-stores-lose-no-write
  (let [dir (temp-dir)
        barrier (CyclicBarrier. writers)
        stores (repeatedly writers #(meeting-first-read (env/file-ledger dir) barrier))
        results (mapv deref (mapv #(future (ledger/change! % k bump)) stores))
        stored (store/read-entry (env/file-ledger dir) k)]
    (is (not-any? anomaly/anomaly? results))
    (is (= {:ledger/entry {:count writers} :ledger/revision writers} stored))
    (testing "given both read the same revision → the loser retried against the winner's entry"
      (is (= #{nil {:count 1}} (set (map :ledger/before results)))))))

(deftest ^{:stratum 2} test-a-corrupt-record-makes-the-ledger-unavailable
  (let [dir (temp-dir)
        s (env/file-ledger dir)]
    (store/write-entry! s k nil entry)
    (spit (disk/record-file (io/file dir) k) "{:not :a-record}")
    (is (= unavailable (reason (store/read-entry s k))))
    (is (= unavailable (reason (store/write-entry! s k 1 entry))))
    (is (= unavailable (reason (store/entries s))))))

(deftest ^{:stratum 2} test-a-directory-it-cannot-create-is-unavailable
  (let [blocker (io/file (temp-dir) "a-file")]
    (spit blocker "")
    (is (= unavailable (reason (store/write-entry! (env/file-ledger (io/file blocker "ledger")) k nil entry))))))
