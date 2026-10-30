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
(ns ai.miniforge.release-executor.crash-matrix-test
  "Recovery scenario C2: a release whose process dies at each point of
   publishing, then resumes. A crash is a JVM Error thrown from the fake
   remote, which (unlike an Exception) escapes the effect transaction
   after its durable claim, leaving the record mid-effect as a dead
   process would. The resume runs the whole release again over the same
   remote and effect store. Each case must end with one PR, or with an
   explicit unresolved failure; never with a success that has no PR or a
   second PR."
  (:require
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.release-executor.core :as core]
   [ai.miniforge.release-executor.fake-remote :as remote]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} crash-on
  "A hook that dies the first time a command starts with `prefix`."
  [prefix]
  (let [fired (atom false)]
    (fn [_state line]
      (when (and (str/starts-with? line prefix) (compare-and-set! fired false true))
        (throw (Error. (str "crash at " prefix)))))))

(defn- ^{:stratum 0} fail-on
  "A hook that makes every command starting with one of `prefixes` fail
   without effect, as against an unreachable remote."
  [& prefixes]
  (fn [_state line]
    (when (some #(str/starts-with? line %) prefixes)
      {:exit-code 128 :stdout "" :stderr "fatal: unable to access the remote"})))

(defn- ^{:stratum 0} one-pr-and-success?
  "The resume succeeded with the one PR, and left no effect record
   mid-effect."
  [{:keys [resumed state]}]
  (and (:success? resumed)
       (= 1 (count (:prs state)))
       (= (get-in resumed [:metrics :pr-url]) (:url (first (:prs state))))
       (not-any? #(#{:proposed :committing :unknown-outcome} (:effect/state %))
                 (fx/list-records (:effect-store-dir state)))))

(defn ^{:stratum 0} release!
  "Release run `origin` over the fake remote `state` with `before`/`after`
   hooks. A crash returns ::crashed."
  [state origin before after]
  (try
    (core/execute-release-phase
     {:workflow/artifacts [] :workflow/spec {:spec/description "Add the feature"}}
     {:executor (remote/executor state before after) :environment-id "fake-env"
      :worktree-path "/tmp/wt" :create-pr? true
      :provenance {:workflow (random-uuid) :origin origin}
      :effect-store-dir (:effect-store-dir @state)}
     {:release-meta {:release/branch-name "feature/x"
                     :release/commit-message "feat: add the feature"
                     :release/pr-title "feat: add the feature"
                     :release/pr-body "## Summary\nAdds the feature"}})
    (catch Error _ ::crashed)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} crash-then-resume
  "Release with a crash hook, then resume (release again) with `resume-before`.
   Returns {:crashed r1 :resumed r2 :state @state}."
  ([before after] (crash-then-resume before after (fn [_ _])))
  ([before after resume-before]
   (let [state  (atom (remote/initial-state))
         origin (random-uuid)
         r1     (release! state origin before after)
         r2     (release! state origin resume-before (fn [_ _]))]
     {:crashed r1 :resumed r2 :state @state})))

(deftest ^{:stratum 1} crash-during-the-checkpoint-test
  (testing "the release finished but the run died before checkpointing it:
            the resume releases again and finds the push and the PR"
    (let [state  (atom (remote/initial-state))
          origin (random-uuid)
          r1     (release! state origin (fn [_ _]) (fn [_ _]))
          r2     (release! state origin (fn [_ _]) (fn [_ _]))]
      (is (:success? r1))
      (is (one-pr-and-success? {:resumed r2 :state @state})))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} crash-before-the-push-reaches-the-remote-test
  (let [run (crash-then-resume (crash-on "git push") (fn [_ _]))]
    (is (= ::crashed (:crashed run)))
    (is (one-pr-and-success? run) "the resume pushes and opens one PR")))

(deftest ^{:stratum 2} crash-after-the-push-reached-the-remote-test
  (let [run (crash-then-resume (fn [_ _]) (crash-on "git push"))]
    (is (= ::crashed (:crashed run)))
    (is (one-pr-and-success? run) "origin confirms the push; one PR")))

(deftest ^{:stratum 2} crash-after-the-pr-was-opened-test
  (let [run (crash-then-resume (fn [_ _]) (crash-on "gh pr create"))]
    (is (= ::crashed (:crashed run)))
    (is (one-pr-and-success? run) "the lookup finds the PR; no second one")))

(deftest ^{:stratum 2} unconfirmable-outcome-is-an-explicit-failure-test
  (testing "crash after the push, and origin unreachable on resume"
    (let [{:keys [resumed state]} (crash-then-resume (fn [_ _]) (crash-on "git push")
                                                     (fail-on "git ls-remote" "git push"))]
      (is (not (:success? resumed)))
      (is (= :push-unresolved (-> resumed :errors first :type)))
      (is (empty? (:prs state)))))
  (testing "crash after the PR opened, and GitHub unreachable on resume"
    (let [{:keys [resumed state]} (crash-then-resume (fn [_ _]) (crash-on "gh pr create")
                                                     (fail-on "gh pr list"))]
      (is (not (:success? resumed)))
      (is (= :pr-lookup-failed (-> resumed :errors first :type)))
      (is (= 1 (count (:prs state)))))))
