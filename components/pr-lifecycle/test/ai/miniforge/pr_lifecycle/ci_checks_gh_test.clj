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
   through the CI check queries and one monitor poll. `fake-gh` stands in
   for babashka.process/shell and rejects --json fields the way gh does."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.ci-checks :as checks]
   [ai.miniforge.pr-lifecycle.ci-monitor :as ci]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} gh-2-102
  "Captured gh output: `gh pr checks --json` for failing, pending and
   passing PRs, gh's error texts, and `gh run view --log-failed`."
  (-> "ai/miniforge/pr_lifecycle/gh-fixtures/gh_2_102.edn" io/resource slurp edn/read-string))

(defn ^{:stratum 0} gh-ok
  "A shell result for a gh command that exited 0 printing `out`."
  [out]
  {:exit 0 :out out :err ""})

(defn ^{:stratum 0} gh-err
  "A shell result for a gh command that exited `exit` printing `err`."
  [exit err]
  {:exit exit :out "" :err err})

(defn ^{:stratum 0} poll-once
  "Poll a fresh monitor for PR `pr-number` once, with `shell-fn` as gh."
  [shell-fn pr-number]
  (with-redefs [process/shell shell-fn]
    (ci/poll-ci-status
     (ci/create-ci-monitor (random-uuid) (random-uuid) (random-uuid) pr-number "/tmp")
     nil)))

(deftest ^{:stratum 0} long-log-keeps-failure-lines-test
  (testing "a failure far from the end of a long log is kept, with the tail"
    (let [filler (apply str (repeat (* 2 checks/max-log-chars) "ok line\n"))
          text (str "Lint\tlint\t##[error]src/a.clj:3:1: error: unresolved symbol\n"
                    "Test\ttest\tFAIL in (foo-test) (foo_test.clj:9)\n"
                    filler "Test\ttest\tRan 900 tests. 1 failure.")
          cleaned (checks/clean-log text)]
      (is (<= (count cleaned) checks/max-log-chars))
      (is (str/includes? cleaned "error: unresolved symbol"))
      (is (str/includes? cleaned "FAIL in (foo-test)"))
      (is (str/ends-with? cleaned "Ran 900 tests. 1 failure.")))))

(deftest ^{:stratum 0} literal-ansi-escapes-are-stripped-test
  (testing "gh writes ESC as a literal ^[ when stdout is a pipe; both forms are removed"
    (is (= "set -o pipefail done" (checks/clean-log "^[[36;1mset -o pipefail^[[0m \u001b[1mdone\u001b[0m")))))

(deftest ^{:stratum 0} log-tail-is-capped-test
  (testing "clean-log keeps only the last max-log-chars characters"
    (let [text (str (apply str (repeat checks/max-log-chars "a")) "the error")]
      (is (= checks/max-log-chars (count (checks/clean-log text))))
      (is (str/ends-with? (checks/clean-log text) "the error")))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} fake-gh
  "babashka.process/shell stand-in answering like gh 2.102. `gh run view`
   returns the captured failed-job log for the captured run id and exit 1
   for any other run. `gh pr checks` exits 1 with gh's error text when a
   --json field is not one gh lists as available, else returns `response`
   ({:exit :out :err})."
  [response]
  (let [stderr (:checks/unknown-field-stderr gh-2-102)
        available (->> (str/split-lines stderr)
                       (drop-while #(not= "Available fields:" %))
                       rest
                       (map str/trim)
                       set)]
    (fn [_opts & args]
      (let [fields (some-> (second (drop-while #(not= "--json" %) args))
                           (str/split #","))]
        (cond
          (not= ["gh" "run" "view"] (take 3 args))
          (if (every? available fields) response (gh-err 1 stderr))

          (= (:run/failed-run-id gh-2-102) (nth args 3))
          (gh-ok (:run/log-failed gh-2-102))

          :else
          (gh-err 1 (str "could not find any workflow run with ID " (nth args 3))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} requested-fields-are-accepted-by-gh-test
  (testing "get-pr-checks asks only for fields gh 2.102 accepts"
    (let [result (with-redefs [process/shell
                               (fake-gh (gh-ok (:checks/failing-json gh-2-102)))]
                   (checks/get-pr-checks "/tmp" 2244))]
      (is (dag/ok? result) (pr-str (:error result)))
      (is (= 7 (count (:checks (:data result))))))))

(deftest ^{:stratum 2} failing-pr-polls-as-failure-test
  (testing "a PR with a failing check polls as :failure naming that check"
    (let [result (poll-once (fake-gh (gh-ok (:checks/failing-json gh-2-102))) 2244)]
      (is (= :failure (:status result)))
      (is (= ["Structure"] (mapv :name (get-in result [:checks :failed]))))
      (is (some? (:event result))))))

(deftest ^{:stratum 2} pending-pr-polls-as-pending-test
  (testing "a PR with running checks polls as :pending"
    (is (= :pending (:status (poll-once (fake-gh (gh-ok (:checks/pending-json gh-2-102))) 2251))))))

(deftest ^{:stratum 2} passing-pr-polls-as-success-test
  (testing "passing checks next to skipped ones poll as :success"
    (is (= :success (:status (poll-once (fake-gh (gh-ok (:checks/passing-json gh-2-102))) 2249))))))

(deftest ^{:stratum 2} checks-not-yet-reported-poll-as-pending-test
  (testing "`no checks reported` right after a push is pending, not unknown"
    (is (= :pending (:status (poll-once (fake-gh (gh-err 1 (:checks/no-checks-stderr gh-2-102))) 2244))))))

(deftest ^{:stratum 2} failing-pr-poll-carries-failed-job-logs-test
  (testing "a :failure poll fetches the failing run's logs into :ci/logs"
    (let [result (poll-once (fake-gh (gh-ok (:checks/failing-json gh-2-102))) 2244)
          logs (:ci/logs result)]
      (is (str/starts-with? logs "## gh run view 40968057793 --log-failed (failed: Structure)\n"))
      (is (str/includes? logs "Polylith workspace check failed with exit code: 1"))
      (is (not (re-find #"\u001b|\d{4}-\d\d-\d\dT\d\d:" logs))
          "ANSI codes and per-line timestamps are stripped")
      (is (= [] (:ci/log-errors result))))))

(deftest ^{:stratum 2} unfetchable-logs-are-reported-test
  (testing "a run gh cannot show leaves :ci/logs nil and records the error"
    (let [failed [{:name "Test" :bucket "fail"
                   :link "https://github.com/miniforge-ai/miniforge/actions/runs/1/job/2"}]
          result (with-redefs [process/shell (fake-gh (gh-ok "[]"))]
                   (checks/get-check-logs "/tmp" failed))]
      (is (nil? (:ci/logs result)))
      (is (= ["1"] (mapv :run-id (:ci/log-errors result)))))))
