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
(ns ai.miniforge.dag-executor.scratch-gc-queue-scope-test
  "The scratch-ref GC queue keeps each repository's entries to itself,
   lives under MINIFORGE_HOME, and reports an unreadable queue instead of
   reading it as empty. Uses throwaway git repositories (init, rev-parse,
   one empty commit for a worktree); `gc-scratch-refs!` is stubbed."
  (:require
   [ai.miniforge.config.interface :as mf-config]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]
   [ai.miniforge.dag-executor.scratch-gc-queue :as sut]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} eight-days-ago
  (java.util.Date. (- (System/currentTimeMillis) (* 8 24 60 60 1000))))

(defn- ^{:stratum 0} git-repo!
  "A new git repository `dir`/`repo-name`; returns its canonical path."
  [dir repo-name]
  (let [repo (fs/file dir repo-name)]
    (fs/create-dirs repo)
    (shell/sh "git" "init" "-q" (str repo))
    (.getCanonicalPath repo)))

(defn- ^{:stratum 0} queued-ids
  [queue]
  (mapv :workflow-id (edn/read-string (slurp queue))))

(deftest ^{:stratum 0} queue-path-follows-miniforge-home-test
  (let [home (str (fs/create-temp-dir))]
    (with-redefs [mf-config/miniforge-home (constantly home)]
      (is (= (str (fs/file home "scratch-gc-queue.edn")) (sut/gc-queue-path))))
    (fs/delete-tree home)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} gc-pass-keeps-other-repositories-entries-test
  (let [dir      (str (fs/create-temp-dir))
        queue    (str dir "/queue.edn")
        repo-a   (git-repo! dir "a")
        repo-b   (git-repo! dir "b")
        gc-calls (atom [])]
    (spit queue (pr-str [{:workflow-id "wf-a" :finished-at eight-days-ago :repo-path repo-a}
                         {:workflow-id "wf-b" :finished-at eight-days-ago :repo-path repo-b}]))
    (with-redefs [sut/gc-queue-path               (constantly queue)
                  scratch-commit/gc-scratch-refs! (fn [repo _]
                                                    (swap! gc-calls conj repo)
                                                    (result/ok {:deleted [] :retained 0}))]
      (let [r (sut/run-deferred-gc! repo-b 7)]
        (testing "a pass in repository B prunes only B's stale entry and GCs only B"
          (is (= ["wf-b"] (:pruned-ids (result/unwrap r))))
          (is (= [repo-b] @gc-calls)))
        (testing "A's entry stays queued for A's own pass"
          (is (= ["wf-a"] (queued-ids queue))))))
    (fs/delete-tree dir)))

(deftest ^{:stratum 1} worktrees-of-one-repository-share-its-entries-test
  (let [dir      (str (fs/create-temp-dir))
        queue    (str dir "/queue.edn")
        repo     (git-repo! dir "a")
        worktree (str dir "/a-worktree")]
    (shell/sh "git" "-C" repo "-c" "user.name=t" "-c" "user.email=t@example.com"
              "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null"
              "commit" "-q" "--allow-empty" "-m" "init")
    (shell/sh "git" "-C" repo "worktree" "add" "-q" worktree)
    (with-redefs [sut/gc-queue-path               (constantly queue)
                  scratch-commit/gc-scratch-refs! (fn [_ _] (result/ok {:deleted [] :retained 0}))]
      (testing "an entry enqueued from a worktree names the repository"
        (sut/enqueue-workflow-gc! "wf-wt" worktree)
        (is (= repo (:repo-path (first (edn/read-string (slurp queue)))))))
      (testing "and a pass from the main checkout prunes it"
        (is (= ["wf-wt"] (:pruned-ids (result/unwrap (sut/run-deferred-gc! repo 0)))))))
    (testing "outside any git repository nothing is enqueued"
      (is (result/err? (sut/enqueue-workflow-gc! "wf-x" (str (fs/create-dirs (fs/file dir "plain")))))))
    (fs/delete-tree dir)))

(deftest ^{:stratum 1} unreadable-queue-is-reported-and-kept-test
  (let [dir     (str (fs/create-temp-dir))
        queue   (str dir "/queue.edn")
        repo    (git-repo! dir "a")
        damaged "[{:workflow-id \"wf-a\" :finished-at"]
    (with-redefs [sut/gc-queue-path (constantly queue)]
      (doseq [[op call] [["enqueue" #(sut/enqueue-workflow-gc! "wf-new")]
                         ["GC pass" #(sut/run-deferred-gc! repo 7)]]]
        (spit queue damaged)
        (let [r      (call)
              copies (fs/glob dir "queue.edn.corrupt-*")]
          (testing op
            (testing "returns an error instead of treating the queue as empty"
              (is (result/err? r)))
            (testing "keeps the unreadable content in a quarantine copy"
              (is (= [damaged] (mapv (comp slurp str) copies)))))
          (run! fs/delete copies)))
      (testing "the queue is usable again afterwards"
        (is (result/ok? (sut/enqueue-workflow-gc! "wf-next")))))
    (fs/delete-tree dir)))
