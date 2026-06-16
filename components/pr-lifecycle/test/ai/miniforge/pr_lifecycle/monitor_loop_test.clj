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

(ns ai.miniforge.pr-lifecycle.monitor-loop-test
  (:require
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.monitor-loop :as sut]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]
   [clojure.test :refer [deftest is testing]]))

(deftest actionable-comments-test
  (testing "change requests and questions are routed, approvals are not"
    (let [classified {:change-requests [{:comment {:id 1}}]
                      :questions [{:comment {:id 2}}]
                      :approvals [{:comment {:id 3}}]}]
      (is (= [1 2]
             (mapv #(get-in % [:comment :id])
                   (#'sut/actionable-comments classified)))))))

(deftest time-budget-stop-result-test
  (testing "time budget exhaustion yields a stopped cycle result"
    (let [result (#'sut/time-budget-stop-result 42)]
      (is (= 0 (:processed result)))
      (is (true? (:stopped? result)))
      (is (= :time-budget-exhausted (:stop-reason result))))))

;; ---------------------------------------------------------------------------
;; Loop lifecycle event emission tests
;; ---------------------------------------------------------------------------

;; A minimal fake PR info that satisfies run-cycle's expectations.
(def ^:private fake-pr
  {:pr/number  99
   :pr/url     "https://github.com/example/repo/pull/99"
   :pr/title   "Test PR"
   :pr/branch  "feat/test"
   :pr/sha     "abc123"
   :pr/updated-at "2026-06-15T00:00:00Z"})

(defn- ok
  "Wrap data in a result map that dag/err? treats as success."
  [data]
  {:ok? true :data data})

(defn- no-prs
  "poll-open-prs stub that always returns an empty PR list."
  [_worktree-path _author]
  (ok {:prs []}))

(defn- one-pr-then-empty
  "Returns a poll-open-prs stub that yields [fake-pr] for n-iters calls, then empty."
  [n-iters]
  (let [call-count (atom 0)]
    (fn [_worktree-path _author]
      (if (< @call-count n-iters)
        (do (swap! call-count inc)
            (ok {:prs [fake-pr]}))
        (ok {:prs []})))))

(defn- no-new-comments
  "poll-pr-for-new-comments stub: no new comments, empty watermarks."
  [_worktree-path _pr-number _watermarks _logger]
  (ok {:new-comments [] :watermarks {}}))

(defn- make-test-monitor
  "Build a monitor atom directly, bypassing classpath config and disk I/O.
   Caller supplies extra-config keys to merge; :event-bus is required for
   emission assertions."
  ([] (make-test-monitor {}))
  ([extra-config]
   (atom
    {:config    (merge {:poll-interval-ms            0
                        :event-bus                   nil
                        :logger                      nil
                        :worktree-path               "/dev/null"
                        :self-author                 "bot"
                        :generate-fn                 nil
                        :max-fix-attempts-per-comment   3
                        :max-total-fix-attempts-per-pr 10
                        :abandon-after-hours           72}
                       extra-config)
     :watermarks {}
     :budgets    {}
     :running?   false
     :started-at nil
     :cycles     0
     :last-cycle-at nil
     :evidence   {:comments-received  0
                  :comments-addressed 0
                  :fixes-pushed       []
                  :questions-answered []}})))

(defn- run-with-stubs
  "Run run-monitor-loop inside with-redefs that replace all I/O-touching poller
   functions.  poll-open-prs-fn controls which PRs each iteration sees."
  [monitor poll-open-prs-fn]
  (with-redefs [poller/poll-open-prs             poll-open-prs-fn
                poller/poll-pr-for-new-comments  no-new-comments
                poller/save-watermarks!          (constantly nil)]
    (sut/run-monitor-loop monitor "bot")))

;; ---------------------------------------------------------------------------
;; (1) Loop start emission

(deftest loop-started-event-emitted-test
  (testing "run-monitor-loop emits :pr-monitor/loop-started on entry before polling"
    (let [bus     (events/create-event-bus)
          monitor (make-test-monitor {:event-bus bus})]
      (run-with-stubs monitor no-prs)
      (let [started-events (filter #(= :pr-monitor/loop-started (:event/type %))
                                   (:events @bus))]
        (is (= 1 (count started-events))
            "exactly one loop-started event")
        (is (nil? (:pr/id (first started-events)))
            "loop-level event carries nil :pr/id")
        (is (map? (:config (first started-events)))
            "loop-started event carries :config map")
        (is (contains? (:config (first started-events)) :poll-interval-ms)
            "config contains :poll-interval-ms")))))

;; ---------------------------------------------------------------------------
;; (2) Per-iteration heartbeat

(deftest cycle-completed-heartbeat-test
  (testing "finalize-loop-iteration! emits :pr-monitor/cycle-completed per iteration"
    (let [n-iters 3
          bus     (events/create-event-bus)
          monitor (make-test-monitor {:event-bus bus})]
      (run-with-stubs monitor (one-pr-then-empty n-iters))
      ;; Iteration-level cycle-completed events have nil :pr/id (distinct from
      ;; the per-PR cycle-completed events emitted by run-cycle, which carry the
      ;; PR number).
      (let [iter-events (->> (:events @bus)
                             (filter #(and (= :pr-monitor/cycle-completed (:event/type %))
                                          (nil? (:pr/id %)))))]
        (is (= n-iters (count iter-events))
            "one heartbeat per iteration")
        (is (= [1 2 3] (mapv :iteration iter-events))
            "iteration values are sequential starting at 1")
        (testing "heartbeat fires even when poll-pr-for-new-comments returns zero comments"
          ;; All no-new-comments stubs return 0; the heartbeat must still appear.
          (is (every? #(nat-int? (:iteration %)) iter-events)))))))

;; ---------------------------------------------------------------------------
;; (3) Loop stop emission

(deftest loop-stopped-event-emitted-test
  (testing "run-monitor-loop emits :pr-monitor/loop-stopped when no open PRs found"
    (let [bus     (events/create-event-bus)
          monitor (make-test-monitor {:event-bus bus})]
      (run-with-stubs monitor no-prs)
      (let [stopped-events (filter #(= :pr-monitor/loop-stopped (:event/type %))
                                   (:events @bus))]
        (is (= 1 (count stopped-events))
            "exactly one loop-stopped event")
        (is (nil? (:pr/id (first stopped-events)))
            "loop-level event carries nil :pr/id")
        (is (= :no-open-prs (:stop/reason (first stopped-events)))
            "stop reason is :no-open-prs")))))

;; ---------------------------------------------------------------------------
;; (4) Manual stop reason (unit-level, no loop needed)

(deftest manual-stop-reason-test
  (testing "stop-monitor-loop sets :running? false and :stop-reason :manual-stop"
    (let [monitor (make-test-monitor {})]
      (swap! monitor assoc :running? true)
      (sut/stop-monitor-loop monitor)
      (is (false? (:running? @monitor))
          ":running? must be false after stop")
      (is (= :manual-stop (:stop-reason @monitor))
          "stop reason must be :manual-stop"))))

;; ---------------------------------------------------------------------------
;; (5) Event ordering

(deftest event-ordering-test
  (testing "loop-started precedes any cycle-completed; loop-stopped is last"
    (let [n-iters 2
          bus     (events/create-event-bus)
          monitor (make-test-monitor {:event-bus bus})]
      (run-with-stubs monitor (one-pr-then-empty n-iters))
      (let [all-types   (mapv :event/type (:events @bus))
            started-idx (.indexOf all-types :pr-monitor/loop-started)
            stopped-idx (.indexOf all-types :pr-monitor/loop-stopped)
            completed-idxs (keep-indexed #(when (= :pr-monitor/cycle-completed %2) %1)
                                         all-types)]
        (is (nat-int? started-idx)
            "loop-started must be present")
        (is (nat-int? stopped-idx)
            "loop-stopped must be present")
        (is (seq completed-idxs)
            "at least one cycle-completed must be present")
        (is (< started-idx (apply min completed-idxs))
            "loop-started appears before the first cycle-completed")
        (is (= stopped-idx (dec (count all-types)))
            "loop-stopped is the final event in the bus")))))
