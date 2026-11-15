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
(ns ai.miniforge.agent-environment.ledger-test
  (:require
   [ai.miniforge.agent-environment.interface.ledger :as store]
   [ai.miniforge.agent-environment.ledger :as ledger]
   [ai.miniforge.agent-environment.ledger.memory :as memory-ledger]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} k [:tenant-a :run-1 :task-1])

(def ^{:stratum 0} rival {:entry/state :state/provisioning :entry/generation 1})

(def ^{:stratum 0} ours {:entry/state :state/provisioned :entry/generation 1})

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(defn- ^{:stratum 0} failing
  "A store answering every call with `failure`."
  [failure]
  (reify store/LedgerStore
    (read-entry [_ _] failure)
    (write-entry! [_ _ _ _] failure)
    (entries [_] failure)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} recording-ours
  [seen]
  (fn [entry]
    (swap! seen conj entry)
    ours))

(defn- ^{:stratum 1} raced
  "A memory store where a rival writes first, at the revision every write
   names, for the first `races` writes."
  [races]
  (let [inner (memory-ledger/create)
        left (atom races)]
    (reify store/LedgerStore
      (read-entry [_ key] (store/read-entry inner key))
      (write-entry! [_ key revision entry]
        (when (pos? (first (swap-vals! left dec)))
          (store/write-entry! inner key revision rival))
        (store/write-entry! inner key revision entry))
      (entries [_] (store/entries inner)))))

(deftest ^{:stratum 1} test-memory-store-writes-only-the-revision-it-read
  (let [s (memory-ledger/create)]
    (is (= {:ledger/entry nil :ledger/revision nil} (store/read-entry s k)))
    (is (= {:ledger/revision 1} (store/write-entry! s k nil rival)))
    (testing "given a second write naming the absent revision → conflict, the first kept"
      (is (= :anomalies.agent-environment/ledger-conflict (reason (store/write-entry! s k nil ours))))
      (is (= rival (:ledger/entry (store/read-entry s k)))))
    (is (= {:ledger/revision 2} (store/write-entry! s k 1 ours)))
    (is (= [{:ledger/key k :ledger/entry ours :ledger/revision 2}] (store/entries s)))))

(deftest ^{:stratum 1} test-change-writes-only-a-difference
  (let [s (memory-ledger/create)]
    (is (= {:ledger/before nil :ledger/after nil} (ledger/change! s k identity)))
    (is (nil? (:ledger/revision (store/read-entry s k))))
    (is (= {:ledger/before nil :ledger/after rival} (ledger/change! s k (constantly rival))))
    (is (= 1 (:ledger/revision (store/read-entry s k))))))

(deftest ^{:stratum 1} test-any-other-store-failure-is-the-ledger-being-unavailable
  (doseq [failure [(anomaly/sub-anomaly :unavailable :anomalies.agent-environment/ledger-unavailable "down" {})
                   (anomaly/sub-anomaly :fault :disk/full "full" {})]]
    (let [s (failing failure)]
      (is (= :anomalies.agent-environment/ledger-unavailable (reason (ledger/entry s k))))
      (is (= :anomalies.agent-environment/ledger-unavailable (reason (ledger/all-entries s))))
      (is (= :anomalies.agent-environment/ledger-unavailable (reason (ledger/change! s k (constantly ours)))))))
  (testing "given a store failure with a ledger reason → returned unchanged"
    (let [down (anomaly/sub-anomaly :unavailable :anomalies.agent-environment/ledger-unavailable "down" {})]
      (is (= down (ledger/entry (failing down) k))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-change-retries-a-lost-race-against-the-winner
  (let [s (raced 1)
        seen (atom [])
        result (ledger/change! s k (recording-ours seen))]
    (is (= [nil rival] @seen))
    (is (= {:ledger/before rival :ledger/after ours} result))
    (is (= ours (ledger/entry s k)))))

(deftest ^{:stratum 2} test-a-key-that-never-settles-is-a-conflict
  (is (= :anomalies.agent-environment/ledger-conflict
         (reason (ledger/change! (raced (inc ledger/write-attempts)) k (constantly ours))))))
