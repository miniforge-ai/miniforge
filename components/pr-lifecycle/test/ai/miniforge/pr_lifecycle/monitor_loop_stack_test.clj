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
(ns ai.miniforge.pr-lifecycle.monitor-loop-stack-test
  "`mf pr monitor` polls once a minute for up to `:abandon-after-hours`
   (72 by default), so the loop must not grow the call stack per poll.
   When the poll steps called each other, the default test JVM stack
   overflowed after about 2,500 polls (about 42 hours at one a minute)."
  (:require
   [ai.miniforge.pr-lifecycle.monitor-loop :as sut]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} polls
  "Polls per run: eight times the count that overflowed the recursive loop."
  20000)

(def ^{:stratum 0} open-pr
  {:pr/number 7 :pr/branch "feat/x" :pr/url "https://github.com/o/r/pull/7"})

(defn- ^{:stratum 0} test-monitor
  "A monitor atom with no event bus, logger, or poll delay."
  []
  (atom {:config   {:poll-interval-ms              0
                    :event-bus                     nil
                    :logger                        nil
                    :worktree-path                 "/dev/null"
                    :self-author                   "bot"
                    :generate-fn                   nil
                    :max-fix-attempts-per-comment  3
                    :max-total-fix-attempts-per-pr 10
                    :abandon-after-hours           72}
         :watermarks {}
         :budgets    {}
         :running?   false
         :cycles     0
         :evidence   {:comments-received  0
                      :comments-addressed 0
                      :fixes-pushed       []
                      :questions-answered []}}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-polls
  "Run the loop with `poll-result` as every open-PR poll's answer, stopping
   it from inside the `polls`th poll. Returns the number of polls made."
  [monitor poll-result]
  (let [calls (atom 0)]
    (with-redefs [poller/poll-open-prs
                  (fn [_worktree-path _author]
                    (when (>= (swap! calls inc) polls)
                      (sut/stop-monitor-loop monitor))
                    poll-result)
                  poller/poll-pr-for-new-comments
                  (fn [& _] {:ok? true :data {:new-comments [] :watermarks {}}})
                  poller/save-watermarks! (constantly nil)]
      (sut/run-monitor-loop monitor "bot"))
    @calls))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} loop-survives-many-polls-with-open-prs-test
  (testing "a poll that finds an open PR and runs its cycle adds no stack per poll"
    (let [monitor (test-monitor)]
      (is (= polls (run-polls monitor {:ok? true :data {:prs [open-pr]}})))
      (is (= polls (:cycles @monitor)))
      (is (= :manual-stop (:stop-reason @monitor))))))

(deftest ^{:stratum 2} loop-survives-many-failed-polls-test
  (testing "a failed open-PR poll retries without adding stack per retry"
    (let [monitor (test-monitor)]
      (is (= polls (run-polls monitor {:ok? false :error {:code :gh-failed}})))
      (is (zero? (:cycles @monitor)))
      (is (= :manual-stop (:stop-reason @monitor))))))
