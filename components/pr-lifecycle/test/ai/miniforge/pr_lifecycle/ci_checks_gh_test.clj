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
(ns ai.miniforge.pr-lifecycle.ci-checks-gh-test
  "Replays real gh 2.102 output, captured from miniforge-ai/miniforge PRs,
   through the CI check queries and one monitor poll."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.ci-checks :as checks]
   [ai.miniforge.pr-lifecycle.gh-replay-test-support :as gh]
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} log-tail-is-capped-test
  (testing "clean-log keeps only the last max-log-chars characters"
    (let [text (str (apply str (repeat checks/max-log-chars "a")) "the error")]
      (is (= checks/max-log-chars (count (checks/clean-log text))))
      (is (str/ends-with? (checks/clean-log text) "the error")))))

(deftest ^{:stratum 0} requested-fields-are-accepted-by-gh-test
  (testing "get-pr-checks asks only for fields gh 2.102 accepts"
    (let [result (with-redefs [process/shell
                               (gh/fake-gh (gh/gh-ok (:checks/failing-json gh/gh-2-102)))]
                   (checks/get-pr-checks "/tmp" 2244))]
      (is (dag/ok? result) (pr-str (:error result)))
      (is (= 7 (count (:checks (:data result))))))))

(deftest ^{:stratum 0} failing-pr-polls-as-failure-test
  (testing "a PR with a failing check polls as :failure naming that check"
    (let [result (gh/poll-once (gh/fake-gh (gh/gh-ok (:checks/failing-json gh/gh-2-102))) 2244)]
      (is (= :failure (:status result)))
      (is (= ["Structure"] (mapv :name (get-in result [:checks :failed]))))
      (is (some? (:event result))))))

(deftest ^{:stratum 0} pending-pr-polls-as-pending-test
  (testing "a PR with running checks polls as :pending"
    (is (= :pending (:status (gh/poll-once (gh/fake-gh (gh/gh-ok (:checks/pending-json gh/gh-2-102))) 2251))))))

(deftest ^{:stratum 0} passing-pr-polls-as-success-test
  (testing "passing checks next to skipped ones poll as :success"
    (is (= :success (:status (gh/poll-once (gh/fake-gh (gh/gh-ok (:checks/passing-json gh/gh-2-102))) 2249))))))

(deftest ^{:stratum 0} checks-not-yet-reported-poll-as-pending-test
  (testing "`no checks reported` right after a push is pending, not unknown"
    (is (= :pending (:status (gh/poll-once (gh/fake-gh (gh/gh-err 1 (:checks/no-checks-stderr gh/gh-2-102))) 2244)))))
  (testing "the plain-output pending exit code is pending, not an error"
    (is (= :pending (:status (gh/poll-once (gh/fake-gh (gh/gh-err checks/checks-pending-exit-code "")) 2244))))))

(deftest ^{:stratum 0} failing-pr-poll-carries-failed-job-logs-test
  (testing "a :failure poll fetches the failing run's logs into :ci/logs"
    (let [result (gh/poll-once (gh/fake-gh (gh/gh-ok (:checks/failing-json gh/gh-2-102))) 2244)
          logs (:ci/logs result)]
      (is (str/starts-with? logs "## gh run view 40968057793 --log-failed (failed: Structure)\n"))
      (is (str/includes? logs "Polylith workspace check failed with exit code: 1"))
      (is (not (re-find #"\u001b|\d{4}-\d\d-\d\dT\d\d:" logs))
          "ANSI codes and per-line timestamps are stripped")
      (is (= [] (:ci/log-errors result))))))

(deftest ^{:stratum 0} unfetchable-logs-are-reported-test
  (testing "a run gh cannot show leaves :ci/logs nil and records the error"
    (let [failed [{:name "Test" :bucket "fail"
                   :link "https://github.com/miniforge-ai/miniforge/actions/runs/1/job/2"}]
          result (with-redefs [process/shell (gh/fake-gh (gh/gh-ok "[]"))]
                   (checks/get-check-logs "/tmp" failed))]
      (is (nil? (:ci/logs result)))
      (is (= ["1"] (mapv :run-id (:ci/log-errors result)))))))
