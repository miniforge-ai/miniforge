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
   reading it as empty. Uses throwaway git repositories, including bare and
   --separate-git-dir ones; `gc-scratch-refs!` is stubbed except where a
   test checks that real scratch refs are deleted."
  (:require
   [ai.miniforge.config.interface :as mf-config]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]
   [ai.miniforge.dag-executor.scratch-gc-queue :as sut]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} eight-days-ago
  (java.util.Date. (- (System/currentTimeMillis) (* 8 24 60 60 1000))))

(defn- ^{:stratum 0} git!
  "Run git with `args`, isolated from the user's and the system's git
   config and from GIT_* variables, with commit signing off and a fixed
   identity. Fails the test when git exits non-zero; returns stdout."
  [& args]
  (let [env (->> (System/getenv)
                 (remove (fn [[k _]] (str/starts-with? k "GIT_")))
                 (into {})
                 (merge {"GIT_CONFIG_GLOBAL" "/dev/null" "GIT_CONFIG_NOSYSTEM" "1"}))
        r   (apply shell/sh "git" "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null"
                   "-c" "user.name=t" "-c" "user.email=t@example.com"
                   (concat args [:env env]))]
    (is (zero? (:exit r)) (str "git " (str/join " " args) ": " (:err r)))
    (str/trim (:out r))))

(defn- ^{:stratum 0} git-dir
  "Canonical path of `path`/.git, the GC queue's key for a plain checkout."
  [path]
  (.getCanonicalPath (fs/file path ".git")))

(defn- ^{:stratum 0} queued-ids
  [queue]
  (mapv :workflow-id (edn/read-string (slurp queue))))

(deftest ^{:stratum 0} queue-path-follows-miniforge-home-test
  (let [home (str (fs/create-temp-dir))]
    (with-redefs [mf-config/miniforge-home (constantly home)]
      (is (= (str (fs/file home "scratch-gc-queue.edn")) (sut/gc-queue-path))))
    (fs/delete-tree home)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} git-repo!
  "A new git repository `dir`/`repo-name` (`git init` plus `init-args`);
   returns its canonical path."
  [dir repo-name & init-args]
  (let [repo (fs/file dir repo-name)]
    (fs/create-dirs repo)
    (apply git! "init" "-q" (concat init-args [(str repo)]))
    (.getCanonicalPath repo)))

(defn- ^{:stratum 1} scratch-refs
  [repo]
  (str/split-lines (git! "-C" repo "for-each-ref" "--format=%(refname)" "refs/miniforge/scratch/")))

(defn- ^{:stratum 1} scratch-ref!
  "Point refs/miniforge/scratch/`workflow-id` in git dir `repo` at a new
   commit of the empty tree, and check that the ref exists."
  [repo workflow-id]
  (let [ref    (str "refs/miniforge/scratch/" workflow-id)
        commit (git! "-C" repo "commit-tree" "4b825dc642cb6eb9a060e54bf8d69288fbee4904" "-m" "scratch")]
    (git! "-C" repo "update-ref" ref commit)
    (is (= ref (git! "-C" repo "for-each-ref" "--format=%(refname)" ref)) "fixture ref created")))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} gc-pass-keeps-other-repositories-entries-test
  (let [dir      (str (fs/create-temp-dir))
        queue    (str dir "/queue.edn")
        repo-a   (git-repo! dir "a")
        repo-b   (git-repo! dir "b")
        gc-calls (atom [])]
    (spit queue (pr-str [{:workflow-id "wf-a" :finished-at eight-days-ago :git-dir (git-dir repo-a)}
                         {:workflow-id "wf-b" :finished-at eight-days-ago :git-dir (git-dir repo-b)}]))
    (with-redefs [sut/gc-queue-path               (constantly queue)
                  scratch-commit/gc-scratch-refs! (fn [repo _]
                                                    (swap! gc-calls conj repo)
                                                    (result/ok {:deleted [] :retained 0}))]
      (let [r (sut/run-deferred-gc! repo-b 7)]
        (testing "a pass in repository B prunes only B's stale entry and GCs only B"
          (is (= ["wf-b"] (:pruned-ids (result/unwrap r))))
          (is (= [(git-dir repo-b)] @gc-calls)))
        (testing "A's entry stays queued for A's own pass"
          (is (= ["wf-a"] (queued-ids queue))))))
    (fs/delete-tree dir)))

(deftest ^{:stratum 2} worktrees-of-one-repository-share-its-entries-test
  (let [dir      (str (fs/create-temp-dir))
        queue    (str dir "/queue.edn")
        repo     (git-repo! dir "a")
        worktree (str dir "/a-worktree")]
    (git! "-C" repo "commit" "-q" "--allow-empty" "-m" "init")
    (git! "-C" repo "worktree" "add" "-q" worktree)
    (with-redefs [sut/gc-queue-path               (constantly queue)
                  scratch-commit/gc-scratch-refs! (fn [_ _] (result/ok {:deleted [] :retained 0}))]
      (testing "an entry enqueued from a worktree names the repository"
        (sut/enqueue-workflow-gc! "wf-wt" worktree)
        (is (= (git-dir repo) (:git-dir (first (edn/read-string (slurp queue)))))))
      (testing "and a pass from the main checkout prunes it"
        (is (= ["wf-wt"] (:pruned-ids (result/unwrap (sut/run-deferred-gc! repo 0)))))))
    (testing "outside any git repository nothing is enqueued"
      (is (result/err? (sut/enqueue-workflow-gc! "wf-x" (str (fs/create-dirs (fs/file dir "plain")))))))
    (fs/delete-tree dir)))

(deftest ^{:stratum 2} unreadable-queue-is-reported-and-kept-test
  (let [dir   (str (fs/create-temp-dir))
        queue (str dir "/queue.edn")
        repo  (git-repo! dir "a")]
    (with-redefs [sut/gc-queue-path (constantly queue)]
      (doseq [damaged ["[{:workflow-id \"wf-a\" :finished-at"
                       "[] [{:workflow-id \"wf-old\"}]"
                       "({:workflow-id \"wf-old\"})"]
              [op call] [["enqueue" #(sut/enqueue-workflow-gc! "wf-new")]
                         ["GC pass" #(sut/run-deferred-gc! repo 7)]]]
        (spit queue damaged)
        (let [r      (call)
              copies (fs/glob dir "queue.edn.corrupt-*")]
          (testing (str op " on a queue holding " damaged)
            (testing "returns an error instead of treating the queue as empty"
              (is (result/err? r)))
            (testing "keeps the unreadable content in a quarantine copy"
              (is (= [damaged] (mapv (comp slurp str) copies)))))
          (run! fs/delete copies)))
      (testing "whitespace and comments around the one vector are fine"
        (spit queue ";; queue\n[]\n;; end\n")
        (is (result/ok? (sut/run-deferred-gc! repo 7))))
      (testing "the queue is usable again afterwards"
        (is (result/ok? (sut/enqueue-workflow-gc! "wf-next")))))
    (fs/delete-tree dir)))

(deftest ^{:stratum 2} bare-repositories-in-one-directory-are-kept-apart-test
  (let [dir   (str (fs/create-temp-dir))
        queue (str dir "/queue.edn")
        bare-a (git-repo! dir "a.git" "--bare")
        bare-b (git-repo! dir "b.git" "--bare")]
    (scratch-ref! bare-a "wf-a")
    (scratch-ref! bare-b "wf-b")
    (spit queue (pr-str [{:workflow-id "wf-a" :finished-at eight-days-ago :git-dir bare-a}
                         {:workflow-id "wf-b" :finished-at eight-days-ago :git-dir bare-b}]))
    (with-redefs [sut/gc-queue-path (constantly queue)]
      (testing "a pass on a.git prunes its entry and deletes its scratch ref"
        (is (= ["wf-a"] (:pruned-ids (result/unwrap (sut/run-deferred-gc! bare-a 0)))))
        (is (= [""] (scratch-refs bare-a))))
      (testing "b.git, in the same directory, keeps its entry and its ref"
        (is (= ["wf-b"] (queued-ids queue)))
        (is (= ["refs/miniforge/scratch/wf-b"] (scratch-refs bare-b)))))
    (fs/delete-tree dir)))

(deftest ^{:stratum 2} separate-git-dir-repository-is-collected-in-its-git-dir-test
  (let [dir     (str (fs/create-temp-dir))
        queue   (str dir "/queue.edn")
        sep-dir (str dir "/sep.git")
        work    (git-repo! dir "work" "--separate-git-dir" sep-dir)
        sep-dir (.getCanonicalPath (fs/file sep-dir))]
    (scratch-ref! sep-dir "wf-sep")
    (with-redefs [sut/gc-queue-path (constantly queue)]
      (testing "an entry enqueued from the checkout names the separate git dir"
        (sut/enqueue-workflow-gc! "wf-sep" work)
        (is (= sep-dir (:git-dir (first (edn/read-string (slurp queue)))))))
      (testing "and a pass from the checkout deletes the ref there"
        (is (= ["wf-sep"] (:pruned-ids (result/unwrap (sut/run-deferred-gc! work 0)))))
        (is (= [""] (scratch-refs sep-dir)))))
    (fs/delete-tree dir)))

(deftest ^{:stratum 2} stale-entry-without-git-dir-triggers-a-sweep-test
  (let [dir      (str (fs/create-temp-dir))
        queue    (str dir "/queue.edn")
        repo     (git-repo! dir "a")
        gc-calls (atom [])]
    (spit queue (pr-str [{:workflow-id "wf-old" :finished-at eight-days-ago}]))
    (with-redefs [sut/gc-queue-path               (constantly queue)
                  scratch-commit/gc-scratch-refs! (fn [target _]
                                                    (swap! gc-calls conj target)
                                                    (result/ok {:deleted [] :retained 0}))]
      (sut/run-deferred-gc! repo 0)
      (testing "a queue holding only pre-upgrade entries still sweeps the repository by age"
        (is (= [(git-dir repo)] @gc-calls)))
      (testing "and keeps the entry, whose repository is unknown"
        (is (= ["wf-old"] (queued-ids queue)))))
    (fs/delete-tree dir)))
