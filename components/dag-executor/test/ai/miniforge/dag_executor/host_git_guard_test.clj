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
(ns ai.miniforge.dag-executor.host-git-guard-test
  "Pins the host-git invariants the worktree executor's environments share
   with the checkout they were cut from.

   The leak is a property of how real git shares a common dir between a
   checkout and its linked worktrees, so the integration cases run real git
   against throwaway repos — a mocked git cannot observe it. The unit cases
   above them cover the comparison itself, where no git is involved."
  (:require
   [ai.miniforge.dag-executor.host-git-fixtures :as fixtures]
   [ai.miniforge.dag-executor.host-git-guard :as sut]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io File]))

;------------------------------------------------------------------------------ Layer 0

;; Local shorthand over the shared fixtures
(def ^{:stratum 0} ^:private host-origin-url fixtures/host-origin-url)

(def ^{:stratum 0} ^:private redirected-origin-url fixtures/redirected-origin-url)

(def ^{:stratum 0} ^:private tracked-ref fixtures/tracked-ref)

(defn- ^{:stratum 0} snapshot!
  "Take a snapshot of `dir`, unwrapped. A snapshot that failed comes back
   as nil and fails the assertion that consumes it, which is a clearer
   report than a throw from inside a fixture."
  [dir]
  (result/unwrap-or (sut/snapshot dir) nil))

(deftest ^{:stratum 0} snapshot-of-a-non-repository-is-an-error-not-an-empty-snapshot-test
  (testing "given a path that is not a git repo → result/err, so the guard fails closed"
    (let [not-a-repo (fixtures/temp-dir!)]
      (try
        (is (result/err? (sut/snapshot not-a-repo)))
        (finally (fixtures/delete-tree! not-a-repo))))))

;------------------------------------------------------------------------------ Layer 1

;; Pure-comparison tests
(deftest ^{:stratum 1} changed-remote-urls-reports-every-kind-of-edit-test
  (testing "given identical url maps → nothing changed"
    (is (= [] (sut/changed-remote-urls {"remote.origin.url" host-origin-url}
                                       {"remote.origin.url" host-origin-url}))))
  (testing "given a redirected url → the key with both values"
    (is (= [{:config-key "remote.origin.url"
             :before     host-origin-url
             :after      redirected-origin-url}]
           (sut/changed-remote-urls {"remote.origin.url" host-origin-url}
                                    {"remote.origin.url" redirected-origin-url}))))
  (testing "given a remote added mid-run → reported, with no before value"
    (is (= [{:config-key "remote.mirror.url" :before nil :after redirected-origin-url}]
           (sut/changed-remote-urls {} {"remote.mirror.url" redirected-origin-url}))))
  (testing "given a remote removed mid-run → reported, with no after value"
    (is (= [{:config-key "remote.origin.url" :before host-origin-url :after nil}]
           (sut/changed-remote-urls {"remote.origin.url" host-origin-url} {})))))

(deftest ^{:stratum 1} moved-remote-refs-ignores-refs-only-one-side-has-test
  (testing "given a ref at a new sha → reported with both shas"
    (is (= [{:ref tracked-ref :before "aaa" :after "bbb"}]
           (sut/moved-remote-refs {tracked-ref "aaa"} {tracked-ref "bbb"}))))
  (testing "given a ref pruned by fetch.prune → not a move"
    (is (= [] (sut/moved-remote-refs {tracked-ref "aaa"} {}))))
  (testing "given a new upstream branch appearing → not a move"
    (is (= [] (sut/moved-remote-refs {} {tracked-ref "aaa"})))))

;; Real-git integration
(deftest ^{:stratum 1} snapshot-captures-remote-urls-and-tracking-refs-test
  (testing "given a checkout with an origin → both halves of the snapshot are populated"
    (let [host (fixtures/init-host-repo! (fixtures/temp-dir!))]
      (try
        (let [snap (snapshot! host)]
          (is (= host-origin-url (get-in snap [:remote-urls "remote.origin.url"])))
          (is (= (fixtures/sha-at host "HEAD~1") (get-in snap [:remote-refs tracked-ref]))))
        (finally (fixtures/delete-tree! host))))))

(deftest ^{:stratum 1} set-url-inside-a-linked-worktree-is-reported-as-drift-test
  (testing "given the 2026-08-06 shape → the host's origin change is caught"
    (let [host    (fixtures/init-host-repo! (fixtures/temp-dir!))
          parent  (fixtures/temp-dir!)
          linked  (str (File. parent "task-worktree"))]
      (try
        (fixtures/git! host "worktree" "add" "--quiet" "--detach" linked)
        (let [before (snapshot! host)
              _      (fixtures/git! linked "remote" "set-url" "origin" redirected-origin-url)
              after  (snapshot! host)
              report (sut/drift before after)]
          (is (= redirected-origin-url (get-in after [:remote-urls "remote.origin.url"]))
              "the leak is real: set-url inside the worktree rewrote the host")
          (is (false? (:clean? report)))
          (is (= [{:config-key "remote.origin.url"
                   :before     host-origin-url
                   :after      redirected-origin-url}]
                 (:remote-url-drift report))))
        (finally
          (fixtures/git! host "worktree" "remove" "--force" linked)
          (fixtures/delete-tree! parent)
          (fixtures/delete-tree! host))))))

(deftest ^{:stratum 1} a-tracking-ref-that-fast-forwards-is-not-drift-test
  (testing "given a fetch advancing origin/main → clean, so normal runs still pass"
    (let [host (fixtures/init-host-repo! (fixtures/temp-dir!))]
      (try
        (let [before (snapshot! host)
              _      (fixtures/git! host "update-ref" tracked-ref (fixtures/sha-at host "HEAD"))
              report (sut/drift before (snapshot! host))]
          (is (true? (:clean? report)))
          (is (= [] (:ref-rewinds report))))
        (finally (fixtures/delete-tree! host))))))

(deftest ^{:stratum 1} a-tracking-ref-forced-backwards-is-drift-test
  (testing "given origin/main force-updated to an earlier commit → drift, with both shas"
    (let [host (fixtures/init-host-repo! (fixtures/temp-dir!))]
      (try
        (let [before  (snapshot! host)
              rewound (fixtures/sha-at host "HEAD~2")
              _       (fixtures/git! host "update-ref" tracked-ref rewound)
              report  (sut/drift before (snapshot! host))
              rewind  (first (:ref-rewinds report))]
          (is (false? (:clean? report)))
          (is (= [] (:remote-url-drift report)))
          (is (= tracked-ref (:ref rewind)))
          (is (= rewound (:after rewind)))
          (is (false? (:fast-forward? rewind))))
        (finally (fixtures/delete-tree! host))))))

(deftest ^{:stratum 1} a-pruned-tracking-ref-is-not-drift-test
  (testing "given fetch.prune retiring a deleted upstream branch → clean"
    (let [host (fixtures/init-host-repo! (fixtures/temp-dir!))]
      (try
        (let [before (snapshot! host)
              _      (fixtures/git! host "update-ref" "-d" tracked-ref)
              report (sut/drift before (snapshot! host))]
          (is (true? (:clean? report))))
        (finally (fixtures/delete-tree! host))))))
