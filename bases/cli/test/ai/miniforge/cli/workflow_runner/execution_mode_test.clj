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
(ns ai.miniforge.cli.workflow-runner.execution-mode-test
  "[:execution :require-governed?] makes governed the default and refuses a
   run that would execute locally, before the run sets anything up; and
   `mf pr respond` hands its --execution-mode and PR branch to the fix run."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.main.commands.pr :as pr]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.cli.workflow-runner.execution-mode :as sut]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private governed-only
  "A user config that requires governed execution."
  {:execution {:require-governed? true}})

(defn- ^{:stratum 0} refusal
  "The anomaly `f` throws, or nil when it returns."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest ^{:stratum 0} pr-respond-hands-on-its-mode-and-branch-test
  (testing "the fix run gets --execution-mode and the checked-out PR branch"
    (let [run (atom nil)]
      (with-redefs [pr/checkout-pr!                          (constantly "fix/review-comments")
                    pr-lifecycle/respond-to-comments!        (fn [_url _cwd run-fn _push! _opts]
                                                               (run-fn {:spec/title "fix"} {:workflow/type :comment-fix})
                                                               {:comments-found 0 :files-processed 0 :fixes []})
                    workflow-runner/run-workflow-from-spec!  (fn [spec opts] (reset! run [spec opts]) {})]
        (pr/pr-respond-cmd {:url "https://github.com/o/r/pull/7" :execution-mode :governed})
        (let [[spec opts] @run]
          (is (= "fix/review-comments" (:spec/branch spec)))
          (is (= :governed (:execution-mode opts)))
          (is (true? (:quiet opts))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} resolve-mode-test
  (testing "without the key, runs stay local unless asked for governed"
    (is (= {:execution-mode :local} (sut/resolve-mode {} {})))
    (is (= {:execution-mode :governed} (sut/resolve-mode {:execution-mode :governed} {}))))
  (testing "with the key, governed is the default and anything else is refused"
    (is (= {:execution-mode :governed} (sut/resolve-mode {} governed-only)))
    (is (= {:execution-mode :governed} (sut/resolve-mode {:execution-mode :governed} governed-only)))
    (is (= {:refused :local} (sut/resolve-mode {:execution-mode :local} governed-only)))))

(deftest ^{:stratum 1} runs-refuse-local-before-setup-test
  (let [setups (atom 0)]
    (with-redefs [config/load-config                   (constantly governed-only)
                  gc-hooks/run-gc-pass-best-effort!   (fn [& _] nil)
                  setup/resolve-workflow-interface    (fn [] (swap! setups inc) {})]
      (testing "a spec run asked to run locally is refused"
        (is (= :anomalies/forbidden
               (:anomaly/category
                (refusal #(workflow-runner/run-workflow-from-spec!
                           {:spec/title "t"} {:execution-mode :local :quiet true}))))))
      (testing "a named workflow, which always runs locally, is refused"
        (is (= :anomalies/forbidden
               (:anomaly/category
                (refusal #(workflow-runner/run-workflow! :canonical-sdlc {:quiet true :output :pretty}))))))
      (is (zero? @setups) "refused before resolving the workflow"))))
