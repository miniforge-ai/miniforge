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
(ns ai.miniforge.codex-gap.interface-test
  "Signal payload shapes mirror the real producers: ReviewIssue maps
   (agent reviewer issues schema), decision-envelope Reason maps, and
   codex consider-response landings (pinned by the codex component's
   tests). Problems corpus entries mirror codex load-graph problem nodes
   (:id :title :open with {:value} items)."
  (:require [ai.miniforge.codex-gap.interface :as gap]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} problems
  [{:id "contract-drift-is-silent"
    :title "Nothing enforces agreement between a producer and its consumers"
    :open [{:value "when producer and consumer live in different repos, the verification has no single place to live"}]}
   {:id "liveness-detection"
    :title "Liveness cannot be inferred from activity"
    :open [{:value "progress is domain-specific; there is no universal signal"}]}])

(def ^{:stratum 0} boundary-landings
  ;; consider-resp for changing-one-side-of-a-boundary: contract drift IS
  ;; a landing; liveness-detection is NOT reachable from that board.
  {:landings [{:id "contract-drift-is-silent" :type "problem"}]})

(defn- ^{:stratum 0} entry [situation consultation signal]
  {:miss/situation situation
   :miss/consultation consultation
   :miss/signal signal})

(def ^{:stratum 0} drift-signal
  {:type :review-blocking
   :payload {:severity :blocking
             :description "consumer reads a key the producer never writes — nothing enforces agreement between producer and consumer"}})

(deftest ^{:stratum 0} missing-ledger-reads-as-empty-not-error
  (is (= {:entries [] :skipped 0} (gap/read-ledger "/nonexistent/run/dir"))))

(deftest ^{:stratum 0} gap-report-confesses-unavailable-anchoring
  (let [r (gap/build-report [] {})]
    (is (= :codex-unavailable (:anchoring r)))
    (is (re-find #"anchoring: codex unavailable" (gap/render-report r)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} no-situation-classifies-uncovered-first
  (testing "rung 1 wins even when delivery also failed"
    (is (= :uncovered
           (:bucket (gap/classify (entry nil nil drift-signal)
                                  nil problems {}))))))

(deftest ^{:stratum 1} confident-unreachable-attribution-is-misrouted-even-when-undelivered
  (let [signal {:type :review-blocking
                :payload {:severity :blocking
                          :description "liveness cannot be inferred from activity; heartbeats reset the clock"}}
        {:keys [bucket attribution]}
        (gap/classify (entry "changing-one-side-of-a-boundary"
                             {:status :skipped :pinned? false :pin-read? nil}
                             signal)
                      boundary-landings problems {})]
    (is (= :misrouted bucket)
        "the board could not have reached it — earlier leak than delivery")
    (is (= "liveness-detection" (:problem attribution)))
    (is (= :similarity (:method attribution)))))

(deftest ^{:stratum 1} skipped-consultation-on-a-reachable-problem-is-undelivered
  (is (= :undelivered
         (:bucket (gap/classify (entry "changing-one-side-of-a-boundary"
                                       {:status :skipped :pinned? false :pin-read? nil}
                                       drift-signal)
                                boundary-landings problems {})))))

(deftest ^{:stratum 1} nil-consultation-with-mapped-situation-is-undelivered
  (is (= :undelivered
         (:bucket (gap/classify (entry "changing-one-side-of-a-boundary"
                                       nil drift-signal)
                                boundary-landings problems {})))))

(deftest ^{:stratum 1} delivered-and-matching-a-landing-is-unheeded
  (let [{:keys [bucket attribution]}
        (gap/classify (entry "changing-one-side-of-a-boundary"
                             {:status :pinned :pinned? true :pin-read? nil}
                             drift-signal)
                      boundary-landings problems {})]
    (is (= :unheeded bucket))
    (is (= "contract-drift-is-silent" (:problem attribution)))))

(deftest ^{:stratum 1} below-threshold-goes-to-the-review-queue
  (let [signal {:type :review-blocking
                :payload {:severity :blocking
                          :description "flaky wifi on the build machine"}}
        {:keys [bucket attribution]}
        (gap/classify (entry "changing-one-side-of-a-boundary"
                             {:status :pinned :pinned? true :pin-read? true}
                             signal)
                      boundary-landings problems {})]
    (is (= :review-queue bucket))
    (is (some? attribution) "the best candidate travels with the queued entry")
    (is (< (:confidence attribution) 0.5))))

(deftest ^{:stratum 1} unavailable-landings-queue-instead-of-convicting-misrouted
  ;; nil or anomaly consider-resp makes rung 2 UNPROVABLE — an empty landing
  ;; set must not convict a confident attribution of :misrouted.
  (doseq [resp [nil {:codex/anomaly :codex-unreadable :codex/reason "gone"}]]
    (let [{:keys [bucket queue-reason attribution]}
          (gap/classify (entry "changing-one-side-of-a-boundary"
                               {:status :pinned :pinned? true :pin-read? true}
                               drift-signal)
                        resp problems {})]
      (is (= :review-queue bucket))
      (is (= :landings-unavailable queue-reason))
      (is (= "contract-drift-is-silent" (:problem attribution))))))

(deftest ^{:stratum 1} mechanical-attribution-wins-for-typed-gate-reasons
  (let [signal {:type :gate-failure
                :payload {:reason/code :reason/grant-absent
                          :reason/detail "no enablement found for repo"}}
        {:keys [bucket attribution]}
        (gap/classify (entry "agent-acts-in-the-world"
                             {:status :pinned :pinned? true :pin-read? true}
                             signal)
                      {:landings [{:id "fail-closed-by-default" :type "problem"}]}
                      problems {})]
    (is (= :mechanical (:method attribution)))
    (is (= "fail-closed-by-default" (:problem attribution)))
    (is (= 1.0 (:confidence attribution)))
    (is (= :unheeded bucket))))

(deftest ^{:stratum 1} ledger-round-trips-and-survives-corrupt-lines
  (let [dir (str (java.nio.file.Files/createTempDirectory
                   "codex-gap-test-"
                   (into-array java.nio.file.attribute.FileAttribute [])))
        e1 (gap/build-entry {:run-id "run-1" :phase :implement
                             :signal drift-signal
                             :situation "changing-one-side-of-a-boundary"
                             :consultation {:status :pinned :pinned? true :pin-read? nil}
                             :bucket :unheeded
                             :attribution {:problem "contract-drift-is-silent"
                                           :method :similarity :confidence 0.61}})]
    (is (= e1 (gap/record-miss! dir e1)))
    (spit (io/file dir "codex-gap-ledger.edn") "{corrupt\n" :append true)
    (let [e2 (gap/build-entry {:run-id "run-1" :phase :review
                               :signal drift-signal :situation nil
                               :consultation nil :bucket :uncovered
                               :attribution nil})]
      (is (= e2 (gap/record-miss! dir e2)))
      (let [{:keys [entries skipped]} (gap/read-ledger dir)]
        (is (= [e1 e2] entries) "corrupt line skipped, order preserved")
        (is (= 1 skipped))
        (is (nil? (get-in entries [0 :miss/consultation :pin-read?]))
            "tri-state pin-read? survives the round trip verbatim")))))

(deftest ^{:stratum 1} build-entry-lifts-pegs-out-of-the-consultation
  ;; SPEC §7.7: the per-peg record accrues in this ledger. The writer
  ;; normalizes — pegs live at :miss/pegs only, never duplicated inside
  ;; the stored consultation.
  (let [pegs [{:id "peg-a" :answer nil :landings {"yes" ["p1"] "no" ["p2"]}}]
        e (gap/build-entry {:run-id "r" :phase :implement
                            :signal drift-signal
                            :situation "changing-one-side-of-a-boundary"
                            :consultation {:status :pinned :pinned? true
                                           :pin-read? nil :pegs pegs}
                            :bucket :unheeded :attribution nil})]
    (is (= pegs (:miss/pegs e)))
    (is (= {:status :pinned :pinned? true :pin-read? nil}
           (:miss/consultation e))
        "one canonical location: the stored consultation drops :pegs")
    (testing "a peg-less consultation stays nil across both keys"
      (let [e (gap/build-entry {:run-id "r" :phase :review
                                :signal drift-signal :situation nil
                                :consultation nil :bucket :uncovered
                                :attribution nil})]
        (is (nil? (:miss/pegs e)))
        (is (nil? (:miss/consultation e)))))))

(deftest ^{:stratum 1} ledger-write-failure-is-data-not-a-throw
  (let [r (gap/record-miss! "/dev/null/not-a-dir" (gap/build-entry
                                                    {:run-id "x" :phase :implement
                                                     :signal drift-signal :situation nil
                                                     :consultation nil :bucket :uncovered
                                                     :attribution nil}))]
    (is (= :ledger-write-failed (:codex-gap/anomaly r)))))

(deftest ^{:stratum 1} gap-report-is-a-vector-with-member-ids-and-no-scalar
  (let [mk (fn [bucket pin-read]
             (gap/build-entry {:run-id "r" :phase :implement
                               :signal drift-signal
                               :situation "s" :consultation {:status :pinned :pin-read? pin-read}
                               :bucket bucket :attribution nil}))
        entries [(mk :uncovered nil) (mk :misrouted nil) (mk :undelivered nil)
                 (mk :unheeded true) (mk :unheeded nil) (mk :review-queue nil)]
        r (gap/build-report entries {:skipped 1 :run-count 2 :anchoring
                                     {:problems 22 :unanchored 4 :speculative 5}})]
    (testing "slot counts"
      (is (= {:covered 5 :total 6} (select-keys (:coverage r) [:covered :total])))
      (is (= 1 (get-in r [:routing :misrouted])))
      (is (= 4 (get-in r [:routing :attributed])))
      (is (= {:read 1 :unread 0 :unknown 1} (get-in r [:delivery :pin-read])))
      (is (= 2 (get-in r [:attention :unheeded])))
      (is (= 2 (get-in r [:learning :open])))
      (is (= 1 (get-in r [:review-queue :count]))))
    (testing "every slot links member miss ids (T2 §4.2)"
      (is (= 1 (count (get-in r [:coverage :misses]))))
      (is (= 1 (count (get-in r [:routing :misses]))))
      (is (= 1 (count (get-in r [:delivery :misses]))))
      (is (= 2 (count (get-in r [:attention :misses]))))
      (is (= 2 (count (get-in r [:learning :misses]))))
      (is (= 1 (count (get-in r [:review-queue :misses])))))
    (testing "no scalar rollup key exists"
      (is (nil? (:score r)))
      (is (nil? (:health r))))
    (testing "renders through the catalog with slots and members"
      (let [text (gap/render-report r)]
        (is (re-find #"Codex gap report — 6 misses across 2 run" text))
        (is (re-find #"coverage: 5 of 6" text))
        (is (re-find #"routing: 1 of 4" text))
        (is (re-find #"anchoring: 22 problems, 4 unanchored, 5 speculative" text))
        (is (re-find #"review queue: 1" text))))))
