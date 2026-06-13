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

(ns ai.miniforge.workflow.observe-phase-test
  (:require
   [ai.miniforge.workflow.observe-phase :as sut]
   [clojure.test :refer [deftest is testing]]))

(def ^:private sample-pr
  ;; Mirrors the production :workflow/pr-info shape built in
  ;; phase-software-factory/release.clj (:pr-number/:pr-url/:branch/:commit-sha).
  {:pr-number 42 :pr-url "https://github.com/o/r/pull/42" :branch "mf/x" :commit-sha "deadbeef"})

(deftest resolve-pr-infos-test
  (testing "DAG path: returns the populated dag-pr-infos"
    (is (= [sample-pr]
           (vec (#'sut/resolve-pr-infos {:execution/dag-pr-infos [sample-pr]})))))
  (testing "single-PR: reads release result output :workflow/pr-info (the real release path)"
    (is (= [sample-pr]
           (#'sut/resolve-pr-infos
            {:execution/phase-results
             {:release {:result {:output {:workflow/pr-info sample-pr}}}}}))))
  (testing "the deprecated [:metrics :release :pr-info] fallback is NO LONGER read
            (one canonical location — the release output)"
    (is (nil? (#'sut/resolve-pr-infos {:metrics {:release {:pr-info sample-pr}}}))))
  (testing "REGRESSION (the #979 handoff bug): the old shallow [:release :pr-info] key is NOT read"
    (is (nil? (#'sut/resolve-pr-infos
               {:execution/phase-results {:release {:pr-info sample-pr}}}))))
  (testing "no PR anywhere -> nil (phase skips)"
    (is (nil? (#'sut/resolve-pr-infos {}))))
  (testing "empty dag-pr-infos falls through to single-PR resolution"
    (is (= [sample-pr]
           (#'sut/resolve-pr-infos
            {:execution/dag-pr-infos []
             :execution/phase-results
             {:release {:result {:output {:workflow/pr-info sample-pr}}}}})))))

(deftest default-config-loaded-from-edn-test
  (testing "observe phase defaults come from resource config"
    (is (= :default (:agent sut/default-config)))
    (is (= 259200 (get-in sut/default-config [:budget :time-seconds])))
    (is (= [] (:gates sut/default-config)))))

(deftest resolve-monitor-config-test
  (testing "context overrides are merged over shared monitor defaults"
    (with-redefs [sut/load-monitor-defaults
                  (fn []
                    {:poll-interval-ms 60000
                     :self-author nil
                     :max-fix-attempts-per-comment 3
                     :max-total-fix-attempts-per-pr 10
                     :abandon-after-hours 72})]
      (let [config (#'sut/resolve-monitor-config
                    {:execution/worktree-path "/tmp/repo"
                     :execution/self-author "miniforge[bot]"
                     :config {:pr-monitor/poll-interval-ms 15000}} nil nil nil)]
        (is (= 15000 (:poll-interval-ms config)))
        (is (= "miniforge[bot]" (:self-author config)))
        (is (= 10 (:max-total-fix-attempts-per-pr config)))))))
