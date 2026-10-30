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

(defn ^{:stratum 0} poll-once
  "Poll a fresh monitor for PR `pr-number` once, with `shell-fn` as gh."
  [shell-fn pr-number]
  (with-redefs [process/shell shell-fn]
    (ci/poll-ci-status
     (ci/create-ci-monitor (random-uuid) (random-uuid) (random-uuid) pr-number "/tmp")
     nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} fake-gh
  "babashka.process/shell stand-in answering `gh pr checks` like gh 2.102:
   exit 1 with gh's error text when a --json field is not one gh lists as
   available, else `response` ({:exit :out :err})."
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
        (if (every? available fields)
          response
          {:exit 1 :out "" :err stderr})))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} requested-fields-are-accepted-by-gh-test
  (testing "get-pr-checks asks only for fields gh 2.102 accepts"
    (let [result (with-redefs [process/shell
                               (fake-gh {:exit 0 :out (:checks/failing-json gh-2-102) :err ""})]
                   (checks/get-pr-checks "/tmp" 2244))]
      (is (dag/ok? result) (pr-str (:error result)))
      (is (= 7 (count (:checks (:data result))))))))

(deftest ^{:stratum 2} failing-pr-polls-as-failure-test
  (testing "a PR with a failing check polls as :failure naming that check"
    (let [result (poll-once (fake-gh {:exit 0 :out (:checks/failing-json gh-2-102) :err ""}) 2244)]
      (is (= :failure (:status result)))
      (is (= ["Structure"] (mapv :name (get-in result [:checks :failed]))))
      (is (some? (:event result))))))

(deftest ^{:stratum 2} pending-pr-polls-as-pending-test
  (testing "a PR with running checks polls as :pending"
    (is (= :pending (:status (poll-once (fake-gh {:exit 0 :out (:checks/pending-json gh-2-102) :err ""}) 2251))))))

(deftest ^{:stratum 2} passing-pr-polls-as-success-test
  (testing "passing checks next to skipped ones poll as :success"
    (is (= :success (:status (poll-once (fake-gh {:exit 0 :out (:checks/passing-json gh-2-102) :err ""}) 2249))))))

(deftest ^{:stratum 2} checks-not-yet-reported-poll-as-pending-test
  (testing "`no checks reported` right after a push is pending, not unknown"
    (is (= :pending (:status (poll-once (fake-gh {:exit 1 :out "" :err (:checks/no-checks-stderr gh-2-102)}) 2244)))))
  (testing "the plain-output pending exit code is pending, not an error"
    (is (= :pending (:status (poll-once (fake-gh {:exit 8 :out "" :err ""}) 2244))))))
