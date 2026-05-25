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

(ns ai.miniforge.dag-executor.scratch-gc-queue-test
  "Unit/integration tests for the deferred GC queue module.

   Tests that require git operations create a temporary git repository and
   clean it up in `finally` blocks.  Tests that exercise only queue I/O
   create a temporary directory (no git needed)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [ai.miniforge.dag-executor.scratch-gc-queue :as sut]
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]
   [ai.miniforge.dag-executor.result :as result])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;;------------------------------------------------------------------------------ Test helpers

(defn- make-temp-dir!
  "Create a temp directory and return its absolute path string."
  []
  (.getAbsolutePath
   (.toFile (Files/createTempDirectory "gc-queue-test-"
                                       (make-array FileAttribute 0)))))

(defn- delete-dir-recursive!
  "Recursively delete `path` and everything under it (best-effort)."
  [path]
  (let [f (File. ^String path)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete ^File child)))))

(defn- make-temp-git-repo!
  "Initialise a minimal git repo in a new temp dir with an initial commit."
  []
  (let [path (make-temp-dir!)]
    (shell/sh "git" "-C" path "init")
    (shell/sh "git" "-C" path "config" "user.email" "test@miniforge.ai")
    (shell/sh "git" "-C" path "config" "user.name" "Miniforge Test")
    (spit (str path "/README.md") "# gc-queue test\n")
    (shell/sh "git" "-C" path "add" ".")
    (shell/sh "git" "-C" path "commit" "-m" "init")
    path))

(defn- queue-file-in
  "Return a File pointing to `gc-queue-test.edn` inside `dir`."
  [dir]
  (File. ^String dir "gc-queue-test.edn"))

(defn- read-raw-queue
  "Slurp and parse the queue file at `path`.  Returns [] when absent."
  [path]
  (let [f (File. ^String path)]
    (if (.exists f)
      (edn/read-string (slurp path))
      [])))

;;------------------------------------------------------------------------------ gc-queue-path

(deftest gc-queue-path-returns-string-test
  (testing "gc-queue-path returns a non-blank string ending in .edn"
    (let [p (sut/gc-queue-path)]
      (is (string? p))
      (is (not (clojure.string/blank? p)))
      (is (clojure.string/ends-with? p ".edn")))))

(deftest gc-queue-path-creates-miniforge-dir-test
  (testing "Calling gc-queue-path creates the ~/.miniforge directory if absent"
    ;; We can't easily test the real home directory; just verify the function
    ;; returns a path whose parent directory exists after the call.
    (let [p (sut/gc-queue-path)
          parent (.getParentFile (File. ^String p))]
      (is (.exists parent))
      (is (.isDirectory parent)))))

;;------------------------------------------------------------------------------ enqueue-workflow-gc!

(deftest enqueue-workflow-gc!-first-entry-test
  (testing "Enqueueing a workflow ID returns ok with queue-size 1"
    (let [tmp-dir  (make-temp-dir!)
          ;; Redirect the queue path by monkey-patching gc-queue-path via with-redefs.
          tmp-path (str tmp-dir "/scratch-gc-queue.edn")]
      (try
        (with-redefs [sut/gc-queue-path (constantly tmp-path)]
          (let [r (sut/enqueue-workflow-gc! "wf-test-001")]
            (is (result/ok? r))
            (let [data (result/unwrap r)]
              (is (= "wf-test-001" (:workflow-id data)))
              (is (= 1 (:queue-size data))))))
        (finally (delete-dir-recursive! tmp-dir))))))

(deftest enqueue-workflow-gc!-accumulates-entries-test
  (testing "Multiple enqueues accumulate entries in the queue"
    (let [tmp-dir  (make-temp-dir!)
          tmp-path (str tmp-dir "/scratch-gc-queue.edn")]
      (try
        (with-redefs [sut/gc-queue-path (constantly tmp-path)]
          (sut/enqueue-workflow-gc! "wf-a")
          (sut/enqueue-workflow-gc! "wf-b")
          (let [r (sut/enqueue-workflow-gc! "wf-c")]
            (is (result/ok? r))
            (is (= 3 (:queue-size (result/unwrap r))))))
        (finally (delete-dir-recursive! tmp-dir))))))

(deftest enqueue-workflow-gc!-writes-workflow-id-and-finished-at-test
  (testing "Enqueued entry contains :workflow-id and a :finished-at timestamp"
    (let [tmp-dir  (make-temp-dir!)
          tmp-path (str tmp-dir "/scratch-gc-queue.edn")]
      (try
        (with-redefs [sut/gc-queue-path (constantly tmp-path)]
          (sut/enqueue-workflow-gc! "wf-ts-test")
          (let [entries (read-raw-queue tmp-path)
                entry   (first entries)]
            (is (= 1 (count entries)))
            (is (= "wf-ts-test" (:workflow-id entry)))
            (is (inst? (:finished-at entry)))))
        (finally (delete-dir-recursive! tmp-dir))))))

;;------------------------------------------------------------------------------ run-deferred-gc! — no stale entries

(deftest run-deferred-gc!-empty-queue-returns-ok-test
  (testing "Empty queue returns ok with pruned=0 and gc-result=nil"
    (let [tmp-dir  (make-temp-dir!)
          tmp-path (str tmp-dir "/scratch-gc-queue.edn")
          repo     (make-temp-git-repo!)]
      (try
        (with-redefs [sut/gc-queue-path (constantly tmp-path)]
          (let [r (sut/run-deferred-gc! repo 7)]
            (is (result/ok? r))
            (let [data (result/unwrap r)]
              (is (= 0 (:pruned data)))
              (is (= 0 (:remaining data)))
              (is (nil? (:gc-result data))))))
        (finally
          (delete-dir-recursive! tmp-dir)
          (delete-dir-recursive! repo))))))

(deftest run-deferred-gc!-fresh-entries-not-pruned-test
  (testing "Entries younger than max-age-days are not pruned"
    (let [tmp-dir  (make-temp-dir!)
          tmp-path (str tmp-dir "/scratch-gc-queue.edn")
          repo     (make-temp-git-repo!)]
      (try
        (with-redefs [sut/gc-queue-path (constantly tmp-path)]
          ;; Enqueue a just-created entry.
          (sut/enqueue-workflow-gc! "wf-fresh")
          ;; Run GC with a large max-age so nothing qualifies.
          (let [r (sut/run-deferred-gc! repo 365)]
            (is (result/ok? r))
            (let [data (result/unwrap r)]
              (is (= 0 (:pruned data)))
              (is (= 1 (:remaining data)))
              (is (nil? (:gc-result data))))))
        (finally
          (delete-dir-recursive! tmp-dir)
          (delete-dir-recursive! repo))))))

;;------------------------------------------------------------------------------ run-deferred-gc! — stale entries

(deftest run-deferred-gc!-zero-age-prunes-all-test
  (testing "max-age-days=0 prunes all entries (age >= 0)"
    (let [tmp-dir  (make-temp-dir!)
          tmp-path (str tmp-dir "/scratch-gc-queue.edn")
          repo     (make-temp-git-repo!)]
      (try
        (with-redefs [sut/gc-queue-path (constantly tmp-path)]
          ;; Create a scratch ref in the repo so gc-scratch-refs! can exercise git.
          (let [fpath (str repo "/test-file.edn")]
            (spit fpath "{:v 1}")
            (scratch-commit/scratch-commit! repo "wf-stale-001" "impl" fpath))
          ;; Enqueue the workflow.
          (sut/enqueue-workflow-gc! "wf-stale-001")
          ;; Run GC with age=0 → stale immediately.
          (let [r (sut/run-deferred-gc! repo 0)]
            (is (result/ok? r))
            (let [data (result/unwrap r)]
              (is (= 1 (:pruned data)))
              (is (= 0 (:remaining data)))
              ;; gc-result from scratch-commit/gc-scratch-refs! should be present.
              (is (map? (:gc-result data)))))
          ;; Queue file should now be empty (fresh=[]).
          (is (= [] (read-raw-queue tmp-path))))
        (finally
          (delete-dir-recursive! tmp-dir)
          (delete-dir-recursive! repo))))))

(deftest run-deferred-gc!-mixed-fresh-and-stale-test
  (testing "Only stale entries are pruned; fresh entries remain in the queue"
    (let [tmp-dir  (make-temp-dir!)
          tmp-path (str tmp-dir "/scratch-gc-queue.edn")
          repo     (make-temp-git-repo!)
          ;; Construct a stale entry manually: finished-at set to 8 days ago.
          eight-days-ago (java.util.Date.
                          (- (System/currentTimeMillis)
                             (* 8 24 60 60 1000)))]
      (try
        (with-redefs [sut/gc-queue-path (constantly tmp-path)]
          ;; Write a stale entry directly to the queue file, bypassing enqueue!
          ;; so we can control the :finished-at timestamp.
          (spit tmp-path (pr-str [{:workflow-id "wf-old"
                                   :finished-at eight-days-ago}]))
          ;; Enqueue a fresh entry via the normal API.
          (sut/enqueue-workflow-gc! "wf-fresh-2")
          ;; Run GC with default 7-day window; "wf-old" is stale, "wf-fresh-2" is not.
          (let [r (sut/run-deferred-gc! repo 7)]
            (is (result/ok? r))
            (let [data (result/unwrap r)]
              (is (= 1 (:pruned data)))
              (is (= 1 (:remaining data)))))
          ;; Only the fresh entry remains in the file.
          (let [remaining (read-raw-queue tmp-path)]
            (is (= 1 (count remaining)))
            (is (= "wf-fresh-2" (:workflow-id (first remaining))))))
        (finally
          (delete-dir-recursive! tmp-dir)
          (delete-dir-recursive! repo))))))

;;------------------------------------------------------------------------------ run-deferred-gc! — arity

(deftest run-deferred-gc!-default-arity-test
  (testing "Single-arity run-deferred-gc! defaults to 7-day retention"
    (let [tmp-dir  (make-temp-dir!)
          tmp-path (str tmp-dir "/scratch-gc-queue.edn")
          repo     (make-temp-git-repo!)]
      (try
        (with-redefs [sut/gc-queue-path (constantly tmp-path)]
          ;; Fresh entry — must not be pruned with default 7-day window.
          (sut/enqueue-workflow-gc! "wf-arity-test")
          (let [r (sut/run-deferred-gc! repo)]
            (is (result/ok? r))
            (is (= 0 (:pruned (result/unwrap r))))))
        (finally
          (delete-dir-recursive! tmp-dir)
          (delete-dir-recursive! repo))))))

;;------------------------------------------------------------------------------ run-deferred-gc! — gc error path

(deftest run-deferred-gc!-gc-failure-leaves-queue-intact-test
  (testing "When gc-scratch-refs! fails the queue is not modified and result is err"
    ;; Write a stale entry manually (8 days old) so it qualifies for GC.
    ;; We mock gc-scratch-refs! to return an error so no git subprocess is
    ;; spawned — shell/sh has no timeout and a hanging git process would
    ;; cause the entire test runner to hang for 30 minutes.
    (let [tmp-dir       (make-temp-dir!)
          tmp-path      (str tmp-dir "/scratch-gc-queue.edn")
          eight-days-ago (java.util.Date.
                          (- (System/currentTimeMillis)
                             (* 8 24 60 60 1000)))]
      (try
        (with-redefs [sut/gc-queue-path            (constantly tmp-path)
                      scratch-commit/gc-scratch-refs! (fn [_ _]
                                                        (result/err
                                                         :scratch-commit/gc-list-failed
                                                         "simulated git failure"
                                                         {}))]
          (spit tmp-path (pr-str [{:workflow-id "wf-stale-err"
                                   :finished-at eight-days-ago}]))
          ;; age=0 → entry is stale immediately; gc-scratch-refs! is mocked to fail.
          (let [r (sut/run-deferred-gc! "/any-path" 0)]
            ;; gc-scratch-refs! failed → result must be err.
            (is (result/err? r))
            ;; The queue file must still contain the stale entry (not wiped).
            (let [remaining (read-raw-queue tmp-path)]
              (is (= 1 (count remaining)))
              (is (= "wf-stale-err" (:workflow-id (first remaining)))))))
        (finally
          (delete-dir-recursive! tmp-dir))))))
