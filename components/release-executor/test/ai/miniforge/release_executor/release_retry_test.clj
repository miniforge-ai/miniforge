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
(ns ai.miniforge.release-executor.release-retry-test
  "A release that runs again for the same run (phase retry or resume) must
   find its branch and PR, not make new ones. Regression: the branch name
   came from the releaser or a random suffix, an existing name got a
   timestamp appended, and only gh's \"already exists\" error led to PR
   reuse, so a second release opened a second PR."
  (:require
   [ai.miniforge.release-executor.core :as core]
   [ai.miniforge.release-executor.fake-remote :as remote]
   [ai.miniforge.release-executor.metadata :as metadata]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} release-meta
  "The releaser's metadata, including a branch name of its own choosing."
  {:release/branch-name "feature/releaser-chosen"
   :release/commit-message "feat: add the feature"
   :release/pr-title "feat: add the feature"
   :release/pr-body "## Summary\nAdds the feature"})

(deftest ^{:stratum 0} release-branch-name-test
  (let [run (random-uuid)
        name (metadata/release-branch-name run nil "Add the feature. More text")]
    (testing "the same run and task give the same branch"
      (is (= name (metadata/release-branch-name run nil "Add the feature. More text"))))
    (testing "another run or task gives another branch"
      (is (not= name (metadata/release-branch-name (random-uuid) nil "Add the feature")))
      (is (not= name (metadata/release-branch-name run "task-2" "Add the feature"))))
    (testing "a safe git branch name"
      (is (re-matches #"mf/[a-z0-9-]+-[0-9a-f]{8}" name)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} release!
  "Release run `run-id` against the fake remote `state`."
  [state run-id]
  (core/execute-release-phase
   {:workflow/artifacts [] :workflow/spec {:spec/description "Add the feature"}}
   {:executor (remote/executor state) :environment-id "fake-env"
    :worktree-path "/tmp/wt" :create-pr? true :provenance {:workflow run-id}}
   {:release-meta release-meta}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} second-release-of-a-run-reuses-branch-and-pr-test
  (let [state  (atom (remote/initial-state))
        run-id (random-uuid)
        once   (release! state run-id)
        twice  (release! state run-id)]
    (is (:success? once))
    (is (:success? twice))
    (is (= 1 (count (:prs @state))) "one PR for the run")
    (is (= 1 (count (:pushed @state))) "one branch pushed")
    (is (= (get-in once [:metrics :pr-url]) (get-in twice [:metrics :pr-url])))
    (is (= (get-in once [:metrics :branch]) (get-in twice [:metrics :branch])))
    (is (not= "feature/releaser-chosen" (get-in once [:metrics :branch]))
        "the branch is the run's, not the releaser's")))

(deftest ^{:stratum 2} different-runs-get-different-branches-test
  (let [state (atom (remote/initial-state))
        a     (release! state (random-uuid))
        _     (swap! state assoc :head "main" :dirty? true)
        b     (release! state (random-uuid))]
    (is (not= (get-in a [:metrics :branch]) (get-in b [:metrics :branch])))
    (is (= 2 (count (:prs @state))))))

(deftest ^{:stratum 2} closed-pr-is-not-replaced-test
  (testing "a branch whose PR was closed fails the release instead of opening another"
    (let [state  (atom (remote/initial-state))
          run-id (random-uuid)
          _      (release! state run-id)
          _      (swap! state update-in [:prs 0] assoc :state "CLOSED")
          again  (release! state run-id)]
      (is (not (:success? again)))
      (is (= :pr-closed (-> again :errors first :type)))
      (is (= 1 (count (:prs @state)))))))

(deftest ^{:stratum 2} merged-pr-is-reused-only-for-its-own-commit-test
  (let [state  (atom (remote/initial-state))
        run-id (random-uuid)
        once   (release! state run-id)
        _      (swap! state update-in [:prs 0] assoc :state "MERGED")]
    (testing "the same commit: the merged PR is the release"
      (let [again (release! state run-id)]
        (is (:success? again))
        (is (= (get-in once [:metrics :pr-url]) (get-in again [:metrics :pr-url])))))
    (testing "a new commit on the branch: no PR holds it, so the release fails"
      (swap! state assoc :dirty? true)
      (let [again (release! state run-id)]
        (is (not (:success? again)))
        (is (= :pr-merged (-> again :errors first :type)))
        (is (= 1 (count (:prs @state))))))))
