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
(ns ai.miniforge.pr-lifecycle.ci-fix-prompt-test
  "Scenario: when a PR's CI fails, the fixer gets a non-empty prompt that
   carries the failed jobs' logs, and it is not run at all without logs.

   Drives controller/run-lifecycle! against replayed gh 2.102 output. The
   inner loop is stubbed so the fixer's task is captured instead of being
   sent to an LLM."
  (:require
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.pr-lifecycle.controller :as controller]
   [ai.miniforge.pr-lifecycle.gh-replay-test-support :as gh]
   [ai.miniforge.pr-lifecycle.messages :as messages]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} scenario-timeout-ms
  "Wall-clock bound on one lifecycle run. A run that reaches neither
   :failed nor :merged would otherwise sleep-loop forever."
  30000)

(def ^{:stratum 0} non-actions-failure-json
  "`gh pr checks --json` for a PR whose only failing check comes from a CI
   provider other than GitHub Actions (link shape from a Cloudflare
   Workers Builds check), so there is no run to fetch logs from."
  (json/generate-string
   [{:bucket "fail" :name "Workers Builds: site" :state "FAILURE" :workflow ""
     :link "https://dash.cloudflare.com/0/workers/services/view/site/production/builds/1"}]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ci-failure-fix-tasks
  "Run the PR lifecycle for an open PR while gh reports `checks-json`.
   Returns {:result lifecycle-result :tasks [fix tasks handed to the fixer]}."
  [checks-json]
  (let [tasks (atom [])
        ctrl (controller/create-controller
              "dag" "run" "task-ci" {:task/id "task-ci" :task/title "Keep the build green"}
              :worktree-path "/tmp"
              :max-fix-iterations 1
              :generate-fn (fn [task _ctx] (swap! tasks conj task) nil))
        run (fn []
              (with-redefs [process/shell (gh/fake-gh (gh/gh-ok checks-json))
                            loop/run-simple (fn [task generate-fn ctx]
                                              (generate-fn task ctx)
                                              {:success false :termination :stubbed})]
                (swap! ctrl assoc :pr {:pr/id 2244 :pr/branch "feat/x" :pr/head-sha "abc"})
                (controller/start-ci-monitoring! ctrl)
                (controller/run-lifecycle! ctrl nil :skip-pr-creation? true)))]
    {:result (deref (future (run)) scenario-timeout-ms ::timed-out)
     :tasks @tasks}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} ci-failure-gives-fixer-failed-job-logs-test
  (testing "a failing GitHub Actions check reaches the fixer with its job's log"
    (let [{:keys [result tasks]} (ci-failure-fix-tasks (:checks/failing-json gh/gh-2-102))
          prompt (:description (first tasks))]
      (is (not= ::timed-out result))
      (is (seq tasks) "the fixer was called")
      (is (not (str/blank? prompt)))
      (is (str/includes? prompt (messages/system-t :fix/ci-logs-heading)))
      (is (str/includes? prompt "## gh run view 40968057793 --log-failed (failed: Structure)"))
      (is (str/includes? prompt "Polylith workspace check failed with exit code: 1")))))

(deftest ^{:stratum 2} ci-failure-without-logs-skips-fixer-test
  (testing "a failure with no fetchable log fails the lifecycle without calling the fixer"
    (let [{:keys [result tasks]} (ci-failure-fix-tasks non-actions-failure-json)]
      (is (= :failed (:status result)))
      (is (empty? tasks)))))
