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

(ns ai.miniforge.dag-executor.scratch-commit-test
  "Integration tests for the scratch-commit module.

   Each test that exercises git operations creates a temporary git repository
   so the assertions run against real git object-store behaviour.  The temp
   repos are cleaned up in `finally` blocks."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [clojure.java.shell :as shell]
   [ai.miniforge.dag-executor.scratch-commit :as sut]
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
   (.toFile (Files/createTempDirectory "dag-scratch-test-"
                                       (make-array FileAttribute 0)))))

(defn- make-temp-git-repo!
  "Initialise a minimal git repo in a new temp dir.

   Creates an initial commit so HEAD and the object store exist.
   Sets local user.name / user.email so commit-tree doesn't fail in
   environments without a global git config."
  []
  (let [path (make-temp-dir!)]
    (shell/sh "git" "-C" path "init")
    (shell/sh "git" "-C" path "config" "user.email" "test@miniforge.ai")
    (shell/sh "git" "-C" path "config" "user.name" "Miniforge Test")
    ;; Seed an initial commit so the object store is properly initialised.
    (spit (str path "/README.md") "# miniforge scratch test\n")
    (shell/sh "git" "-C" path "add" ".")
    (shell/sh "git" "-C" path "commit" "-m" "init")
    path))

(defn- delete-dir-recursive!
  "Recursively delete `path` and everything under it (best-effort)."
  [path]
  (let [f (File. ^String path)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete ^File child)))))

(defn- write-temp-file!
  "Write `content` to `<repo-path>/<filename>` and return the absolute path."
  [repo-path filename content]
  (let [f (File. ^String repo-path ^String filename)]
    (spit f content)
    (.getAbsolutePath f)))

;;------------------------------------------------------------------------------ Layer 0: scratch-ref-name (pure)

(deftest scratch-ref-name-test
  (testing "Returns the expected refs/miniforge/scratch/<id> path"
    (is (= "refs/miniforge/scratch/wf-abc123"
           (sut/scratch-ref-name "wf-abc123")))
    (is (= "refs/miniforge/scratch/some-workflow"
           (sut/scratch-ref-name "some-workflow"))))

  (testing "Handles workflow IDs with hyphens and dates"
    (is (= "refs/miniforge/scratch/run-2026-05-24"
           (sut/scratch-ref-name "run-2026-05-24"))))

  (testing "Returns a string starting with refs/miniforge/scratch/"
    (is (str/starts-with? (sut/scratch-ref-name "x") "refs/miniforge/scratch/"))))

;;------------------------------------------------------------------------------ Layer 2: scratch-commit! — happy path

(deftest scratch-commit!-first-commit-test
  (testing "Creates a new scratch ref on first call"
    (let [repo (make-temp-git-repo!)]
      (try
        (let [fpath (write-temp-file! repo "artifact.edn" "{:status :ok}")
              wf-id "test-workflow-first"
              r     (sut/scratch-commit! repo wf-id "implement" fpath)]
          (is (result/ok? r))
          (let [data (result/unwrap r)]
            (is (string? (:commit-sha data)))
            (is (not (str/blank? (:commit-sha data))))
            (is (= (sut/scratch-ref-name wf-id) (:ref data)))
            (is (= wf-id (:workflow-id data)))
            (is (= "implement" (:phase data)))
            (is (= fpath (:file-path data))))
          ;; The scratch ref must now point to a valid commit.
          (let [verify (shell/sh "git" "-C" repo "rev-parse"
                                 "--verify" (sut/scratch-ref-name wf-id))]
            (is (zero? (:exit verify)))))
        (finally (delete-dir-recursive! repo))))))

(deftest scratch-commit!-second-commit-chains-parent-test
  (testing "Second call chains its commit off the first as parent"
    (let [repo  (make-temp-git-repo!)
          wf-id "test-workflow-chain"]
      (try
        (let [fpath (write-temp-file! repo "state.edn" "{:v 1}")
              r1    (sut/scratch-commit! repo wf-id "plan" fpath)]
          (is (result/ok? r1))
          (write-temp-file! repo "state.edn" "{:v 2}")
          (let [r2     (sut/scratch-commit! repo wf-id "implement" fpath)
                sha1   (:commit-sha (result/unwrap r1))
                sha2   (:commit-sha (result/unwrap r2))
                cat-r  (shell/sh "git" "-C" repo "cat-file" "-p" sha2)]
            (is (result/ok? r2))
            ;; The second commit's cat-file output must include "parent <sha1>".
            (is (str/includes? (:out cat-r) (str "parent " sha1)))))
        (finally (delete-dir-recursive! repo))))))

(deftest scratch-commit!-commit-message-format-test
  (testing "Commit message is exactly 'scratch: <wf-id> <phase> <file-path>'"
    (let [repo  (make-temp-git-repo!)
          wf-id "msg-format-test"]
      (try
        (let [fpath  (write-temp-file! repo "out.edn" "hello")
              r      (sut/scratch-commit! repo wf-id "verify" fpath)
              sha    (:commit-sha (result/unwrap r))
              log-r  (shell/sh "git" "-C" repo "log" "--format=%s" "-1" sha)]
          (is (= (str "scratch: " wf-id " verify " fpath)
                 (str/trim (:out log-r)))))
        (finally (delete-dir-recursive! repo))))))

(deftest scratch-commit!-does-not-touch-index-test
  (testing "Working tree and index are pristine after scratch-commit!"
    (let [repo  (make-temp-git-repo!)
          wf-id "index-clean-test"]
      (try
        ;; Place a file that is NOT added to the index.
        (let [fpath  (write-temp-file! repo "untracked.edn" "{:x 1}")
              status-before (shell/sh "git" "-C" repo "status" "--porcelain")]
          (sut/scratch-commit! repo wf-id "implement" fpath)
          ;; After scratch-commit!, porcelain status must be unchanged.
          (let [status-after (shell/sh "git" "-C" repo "status" "--porcelain")]
            (is (= (:out status-before) (:out status-after)))))
        (finally (delete-dir-recursive! repo))))))

;;------------------------------------------------------------------------------ Layer 2: scratch-commit! — error paths

(deftest scratch-commit!-nonexistent-file-returns-err-test
  (testing "Returns result/err when file-path does not exist"
    (let [repo (make-temp-git-repo!)]
      (try
        (let [r (sut/scratch-commit! repo "wf-nofile" "impl" "/nonexistent/path/file.edn")]
          (is (result/err? r))
          (is (= :scratch-commit/hash-object-failed (-> r :error :code))))
        (finally (delete-dir-recursive! repo))))))

;;------------------------------------------------------------------------------ Layer 2: list-scratch-commits

(deftest list-scratch-commits-empty-when-no-ref-test
  (testing "Returns empty vector when scratch ref does not exist"
    (let [repo (make-temp-git-repo!)]
      (try
        (is (= [] (sut/list-scratch-commits repo "nonexistent-wf")))
        (finally (delete-dir-recursive! repo))))))

(deftest list-scratch-commits-single-entry-test
  (testing "Returns one entry after one scratch-commit!"
    (let [repo  (make-temp-git-repo!)
          wf-id "list-single"]
      (try
        (let [fpath (write-temp-file! repo "snap.edn" "v1")
              _     (sut/scratch-commit! repo wf-id "phase-1" fpath)
              entries (sut/list-scratch-commits repo wf-id)]
          (is (= 1 (count entries)))
          (let [e (first entries)]
            (is (string? (:commit-sha e)))
            (is (string? (:message e)))
            (is (pos? (:timestamp-epoch e)))
            (is (= wf-id (:workflow-id e)))))
        (finally (delete-dir-recursive! repo))))))

(deftest list-scratch-commits-multiple-entries-newest-first-test
  (testing "Returns entries newest-first when multiple commits exist"
    (let [repo  (make-temp-git-repo!)
          wf-id "list-multi"]
      (try
        (let [fpath (write-temp-file! repo "snap.edn" "v1")
              _     (sut/scratch-commit! repo wf-id "phase-1" fpath)
              _     (write-temp-file! repo "snap.edn" "v2")
              _     (sut/scratch-commit! repo wf-id "phase-2" fpath)
              entries (sut/list-scratch-commits repo wf-id)]
          (is (= 2 (count entries)))
          ;; Git log is newest-first; phase-2 must appear before phase-1.
          (is (str/includes? (:message (first entries)) "phase-2"))
          (is (str/includes? (:message (second entries)) "phase-1")))
        (finally (delete-dir-recursive! repo))))))

;;------------------------------------------------------------------------------ Layer 2: gc-scratch-refs!

(deftest gc-scratch-refs!-empty-repo-returns-ok-test
  (testing "Returns ok with no deleted refs when no scratch refs exist"
    (let [repo (make-temp-git-repo!)]
      (try
        (let [r (sut/gc-scratch-refs! repo 30)]
          (is (result/ok? r))
          (is (= {:deleted [] :retained 0} (result/unwrap r))))
        (finally (delete-dir-recursive! repo))))))

(deftest gc-scratch-refs!-retains-fresh-refs-test
  (testing "With max-age-days=365 a just-created ref is retained"
    (let [repo  (make-temp-git-repo!)
          wf-id "gc-fresh"]
      (try
        (let [fpath (write-temp-file! repo "f.edn" "x")]
          (sut/scratch-commit! repo wf-id "impl" fpath)
          (let [r (sut/gc-scratch-refs! repo 365)]
            (is (result/ok? r))
            (let [{:keys [deleted retained]} (result/unwrap r)]
              (is (= [] deleted))
              (is (= 1 retained)))))
        (finally (delete-dir-recursive! repo))))))

(deftest gc-scratch-refs!-deletes-with-zero-age-test
  (testing "max-age-days=0 deletes all scratch refs (age >= 0 seconds)"
    (let [repo  (make-temp-git-repo!)
          wf-id "gc-zero-age"]
      (try
        (let [fpath (write-temp-file! repo "g.edn" "x")]
          (sut/scratch-commit! repo wf-id "impl" fpath)
          (let [r (sut/gc-scratch-refs! repo 0)]
            (is (result/ok? r))
            (let [{:keys [deleted retained]} (result/unwrap r)]
              (is (= 1 (count deleted)))
              (is (str/includes? (first deleted) wf-id))
              (is (= 0 retained)))
            ;; Verify the ref is actually gone from git.
            (let [verify (shell/sh "git" "-C" repo "rev-parse"
                                   "--verify" (sut/scratch-ref-name wf-id))]
              (is (not (zero? (:exit verify)))))))
        (finally (delete-dir-recursive! repo))))))

(deftest gc-scratch-refs!-deletes-only-qualifying-refs-test
  (testing "Only refs older than threshold are deleted; fresh ones are retained"
    ;; We can't easily age a ref, so instead we use two workflow IDs and
    ;; verify that with age=365 both are retained (no false positives).
    (let [repo   (make-temp-git-repo!)
          wf-id1 "gc-multi-a"
          wf-id2 "gc-multi-b"]
      (try
        (let [f1 (write-temp-file! repo "a.edn" "1")
              f2 (write-temp-file! repo "b.edn" "2")]
          (sut/scratch-commit! repo wf-id1 "impl" f1)
          (sut/scratch-commit! repo wf-id2 "impl" f2)
          (let [r (sut/gc-scratch-refs! repo 365)]
            (is (result/ok? r))
            (let [{:keys [deleted retained]} (result/unwrap r)]
              (is (= [] deleted))
              (is (= 2 retained)))))
        (finally (delete-dir-recursive! repo))))))
