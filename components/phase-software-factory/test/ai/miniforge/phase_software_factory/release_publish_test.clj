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
(ns ai.miniforge.phase-software-factory.release-publish-test
  (:require
   [ai.miniforge.phase-software-factory.release-publish :as release-publish]
   [ai.miniforge.phase-software-factory.release-unpushed :as release-unpushed]
   [ai.miniforge.release-executor.interface :as release-executor]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} provenance-origin-is-the-run-lineage-test
  (let [origin (fn [ctx]
                 (get-in (release-publish/build-publish-context
                          (assoc ctx :execution/opts {:github-token "t"}) {:worktree-path "/wt"} nil)
                         [:provenance :origin]))]
    (testing "a DAG task: the parent's lineage from its input"
      (is (= "parent-origin" (origin {:execution/id "task-run"
                                      :execution/origin-id "task-run"
                                      :execution/input {:workflow/origin-id "parent-origin"}}))))
    (testing "a DAG task checkpointed before lineage was recorded: its parent"
      (is (= "parent-run" (origin {:execution/id "task-run"
                                   :execution/origin-id "task-run"
                                   :execution/input {:workflow/parent-id "parent-run"}}))))
    (testing "a resumed attempt: the run it continues"
      (is (= "first-run" (origin {:execution/id "attempt-2" :execution/origin-id "first-run"}))))
    (testing "a context with no lineage recorded: its own id"
      (is (= "run" (origin {:execution/id "run"}))))))

(deftest ^{:stratum 0} create-pr-option-test
  (let [create-pr? (fn [opts config]
                     (:create-pr? (release-publish/build-publish-context
                                   {:execution/id "run" :execution/opts (assoc opts :github-token "t")}
                                   (assoc config :worktree-path "/wt") nil)))]
    (testing "an explicit false in the execution opts is honoured"
      (is (false? (create-pr? {:create-pr? false} {}))))
    (testing "absent from opts: the phase config, else true"
      (is (false? (create-pr? {} {:create-pr? false})))
      (is (true? (create-pr? {} {}))))))

(deftest ^{:stratum 0} publish-requires-the-pr-it-was-asked-for-test
  (let [publish (fn [create-pr? exec-result]
                  (with-redefs [release-executor/publish-release (constantly exec-result)]
                    (get-in (release-publish/publish-when-allowed
                             {:phase {:result {:status :success
                                               :output {:release/prepared {}}}}
                              :execution/id "run"
                              :execution/opts {:github-token "t" :create-pr? create-pr?}
                              :worktree-path "/wt"})
                            [:phase :result])))
        no-pr {:success? true :artifacts [{:artifact/content {:branch "b"}}] :metrics {}}]
    (testing "asked for a PR, none reported: the release fails"
      (is (= :release/pr-missing (get-in (publish true no-pr) [:error :data :type]))))
    (testing "no PR asked for: the push alone completes"
      (is (= :completed (get-in (publish false no-pr) [:output :release/status]))))))

(deftest ^{:stratum 0} name-status-entries-test
  (is (= [["M" "src/a.clj"] ["A" "src/b.clj"] ["R" "src/new.clj"] ["D" "old.clj"]]
         (release-unpushed/name-status-entries
          "M\u0000src/a.clj\u0000A\u0000src/b.clj\u0000R100\u0000src/old.clj\u0000src/new.clj\u0000D\u0000old.clj\u0000"))))
