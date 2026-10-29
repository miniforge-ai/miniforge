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
(ns ai.miniforge.pr-lifecycle.fix-loop-stage-test
  "The fix loop commits what the fix wrote, and only that, in a real git
   repository. Generation and push are stubbed."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.fix-loop :as fix]
   [ai.miniforge.pr-lifecycle.fix-stage :as fix-stage]
   [ai.miniforge.release-executor.interface :as release]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} git!
  "Run git in `dir` without the repository variables a git hook exports;
   returns trimmed stdout."
  [dir & args]
  (str/trim (str (:out (apply process/sh
                              {:dir (str dir)
                               :env (apply dissoc (into {} (System/getenv))
                                           ["GIT_DIR" "GIT_WORK_TREE" "GIT_INDEX_FILE"
                                            "GIT_COMMON_DIR"])}
                              "git" args)))))

(def ^{:stratum 0} fix-files
  "A fix that modifies, creates and deletes, plus a name with a glob
   character that would match every .txt file as a pathspec."
  [{:path "a.txt" :content "a2\n" :action :modify}
   {:path "b.txt" :content "b\n" :action :create}
   {:path "c.txt" :action :delete}
   {:path "*.txt" :content "star\n" :action :create}])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} repo-with-unrelated-change
  "A temp repository with a.txt, c.txt and d.txt committed, and d.txt then
   modified outside the fix."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "fix-loop-stage-"}))]
    (git! dir "init" "-q")
    (git! dir "config" "user.email" "t@example.com")
    (git! dir "config" "user.name" "t")
    (doseq [f ["a.txt" "c.txt" "d.txt"]]
      (spit (str (fs/path dir f)) (str f "\n")))
    (git! dir "add" "a.txt" "c.txt" "d.txt")
    (git! dir "commit" "-q" "-m" "init")
    (spit (str (fs/path dir "d.txt")) "edited by someone else\n")
    dir))

(deftest ^{:stratum 1} commit-fix-commits-a-fix-in-a-clean-repository-test
  (testing "a fresh repository with nothing staged commits the fix"
    (let [dir (str (fs/create-temp-dir {:prefix "fix-loop-clean-"}))]
      (try
        (git! dir "init" "-q")
        (git! dir "config" "user.email" "t@example.com")
        (git! dir "config" "user.name" "t")
        (spit (str (fs/path dir "a.txt")) "a\n")
        (git! dir "add" "a.txt")
        (git! dir "commit" "-q" "-m" "init")
        (spit (str (fs/path dir "a.txt")) "a2\n")
        (with-redefs [release/push-branch! (fn [& _] {:success? true})]
          (let [result (fix/commit-fix dir {:fix/type :review-changes} "fix/x" nil
                                       [{:path "a.txt" :action :modify}])]
            (is (dag/ok? result))
            (is (= "M\ta.txt" (git! dir "show" "--name-status" "--format=" "HEAD")))
            (is (= "" (git! dir "status" "--short")))
            (is (not (fs/exists? (fs/path dir ".git" "index.lock"))))))
        (finally (fs/delete-tree dir))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} run-fix-loop-commits-exactly-the-files-the-fix-wrote-test
  (let [dir (repo-with-unrelated-change)]
    (try
      (with-redefs [fix/generate-fix (fn [& _] {:success true
                                                :artifact {:code/files fix-files}
                                                :metrics {}})
                    release/push-branch! (fn [& _] {:success? true})]
        (let [result (fix/run-fix-loop {:task/id (random-uuid)}
                                       {:pr/id 1 :pr/branch "fix/x"}
                                       {:type :review-changes} (fn [& _]) {}
                                       :worktree-path dir :max-attempts 1)]
          (testing "the fix is committed"
            (is (:success? result))
            (is (= (git! dir "rev-parse" "HEAD") (:commit-sha result))))
          (testing "the commit holds the fix's files and nothing else"
            (is (= #{"M\ta.txt" "A\tb.txt" "D\tc.txt" "A\t*.txt"}
                   (set (str/split-lines (git! dir "show" "--name-status" "--format=" "HEAD"))))))
          (testing "the unrelated change stays unstaged"
            (is (= "M d.txt" (git! dir "status" "--short" "--" "d.txt"))))))
      (finally (fs/delete-tree dir)))))

(deftest ^{:stratum 2} commit-fix-reports-a-staging-failure-test
  (testing "a path git refuses to stage fails the commit step as :stage-failed"
    (let [dir (repo-with-unrelated-change)]
      (try
        (let [result (fix/commit-fix dir {:fix/type :review-changes} "fix/x" nil
                                     [{:path "missing.txt" :action :modify}])]
          (is (dag/err? result))
          (is (= :stage-failed (get-in result [:error :code]))))
        (finally (fs/delete-tree dir))))))

(deftest ^{:stratum 2} commit-fix-leaves-the-index-untouched-when-staging-fails-test
  (testing "when the second file cannot be staged, the index keeps what it had and nothing more"
    (let [dir (repo-with-unrelated-change)]
      (try
        (spit (str (fs/path dir ".gitignore")) "ignored.txt\n")
        (spit (str (fs/path dir "a.txt")) "a2\n")
        (spit (str (fs/path dir "ignored.txt")) "x\n")
        (let [head   (git! dir "rev-parse" "HEAD")
              before (git! dir "ls-files" "--stage")
              result (fix/commit-fix dir {:fix/type :review-changes} "fix/x" nil
                                     [{:path "a.txt" :action :modify}
                                      {:path "ignored.txt" :action :create}])]
          (is (= :stage-failed (get-in result [:error :code])))
          (is (= before (git! dir "ls-files" "--stage")))
          (is (= "" (git! dir "diff" "--cached" "--name-only")))
          (is (= head (git! dir "rev-parse" "HEAD")))
          (is (not (fs/exists? (fs/path dir ".git" "index.lock")))))
        (finally (fs/delete-tree dir))))))

(deftest ^{:stratum 2} run-fix-loop-refuses-a-fix-through-a-symlink-test
  (testing "a fix to link.txt -> target.txt is refused before anything is written or staged"
    (let [dir (repo-with-unrelated-change)]
      (try
        (spit (str (fs/path dir "target.txt")) "t1\n")
        (fs/create-sym-link (fs/path dir "link.txt") "target.txt")
        (git! dir "add" "target.txt" "link.txt")
        (git! dir "commit" "-q" "-m" "link")
        (let [head (git! dir "rev-parse" "HEAD")]
          (with-redefs [fix/generate-fix (fn [& _] {:success true
                                                    :artifact {:code/files [{:path "link.txt"
                                                                             :content "t2\n"
                                                                             :action :modify}]}
                                                    :metrics {}})
                        release/push-branch! (fn [& _] {:success? true})]
            (let [result (fix/run-fix-loop {:task/id (random-uuid)}
                                           {:pr/id 1 :pr/branch "fix/x"}
                                           {:type :review-changes} (fn [& _]) {}
                                           :worktree-path dir :max-attempts 1)]
              (is (not (:success? result)))
              (is (= "t1\n" (slurp (str (fs/path dir "target.txt")))))
              (is (= head (git! dir "rev-parse" "HEAD"))))))
        (finally (fs/delete-tree dir))))))

(deftest ^{:stratum 2} commit-fix-fails-cleanly-when-the-index-is-locked-test
  (testing "another process's index.lock makes the fix fail without touching the index or the lock"
    (let [dir  (repo-with-unrelated-change)
          lock (fs/path dir ".git" "index.lock")]
      (try
        (spit (str lock) "held by someone else")
        (spit (str (fs/path dir "a.txt")) "a2\n")
        (let [head   (git! dir "rev-parse" "HEAD")
              before (vec (fs/read-all-bytes (fs/path dir ".git" "index")))
              result (fix/commit-fix dir {:fix/type :review-changes} "fix/x" nil
                                     [{:path "a.txt" :action :modify}])]
          (is (= :stage-failed (get-in result [:error :code])))
          (is (= before (vec (fs/read-all-bytes (fs/path dir ".git" "index")))))
          (is (= "held by someone else" (slurp (str lock))))
          (is (= head (git! dir "rev-parse" "HEAD"))))
        (finally (fs/delete-tree dir))))))

(deftest ^{:stratum 2} commit-fix-works-from-a-relative-worktree-path-test
  (testing "a worktree path relative to the process directory stages into the right index"
    ;; Nest the repository so a path relative to this process's directory
    ;; does not also resolve from inside the repository by coincidence.
    (let [base (repo-with-unrelated-change)
          dir  (str (fs/path base "a" "b" "c" "repo"))]
      (fs/create-dirs (fs/parent dir))
      (doseq [entry (fs/list-dir base)
              :when (not= "a" (str (fs/file-name entry)))]
        (fs/move entry (fs/path (fs/create-dirs dir) (fs/file-name entry))))
      (try
        (spit (str (fs/path dir "a.txt")) "a2\n")
        (let [relative (str (fs/relativize (fs/canonicalize ".") (fs/canonicalize dir)))]
          (with-redefs [release/push-branch! (fn [& _] {:success? true})]
            (let [result (fix/commit-fix relative {:fix/type :review-changes} "fix/x" nil
                                         [{:path "a.txt" :action :modify}])]
              (is (dag/ok? result))
              (is (= "M\ta.txt" (git! dir "show" "--name-status" "--format=" "HEAD")))
              (is (not (fs/exists? (fs/path dir ".git" "index.lock")))))))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 2} commit-fix-refuses-when-changes-are-already-staged-test
  (testing "an unrelated pre-staged file makes the fix fail; it is neither committed nor unstaged"
    (let [dir (repo-with-unrelated-change)]
      (try
        (git! dir "add" "d.txt")
        (spit (str (fs/path dir "a.txt")) "a2\n")
        (let [head   (git! dir "rev-parse" "HEAD")
              before (vec (fs/read-all-bytes (fs/path dir ".git" "index")))
              result (fix/commit-fix dir {:fix/type :review-changes} "fix/x" nil
                                     [{:path "a.txt" :action :modify}])]
          (is (= :stage-failed (get-in result [:error :code])))
          (is (re-find #"already has staged changes" (str (get-in result [:error :message]))))
          (is (= before (vec (fs/read-all-bytes (fs/path dir ".git" "index")))))
          (is (= "d.txt" (git! dir "diff" "--cached" "--name-only")))
          (is (= head (git! dir "rev-parse" "HEAD")))
          (is (not (fs/exists? (fs/path dir ".git" "index.lock")))))
        (finally (fs/delete-tree dir))))))

(deftest ^{:stratum 2} stage-files-checks-the-real-index-whatever-git-index-file-says-test
  (testing "GIT_INDEX_FILE pointing at a clean alternate index does not hide a staged file"
    (let [dir       (repo-with-unrelated-change)
          alternate (str (fs/path dir ".git" "alternate-index"))]
      (try
        (git! dir "add" "d.txt")
        (process/sh {:dir dir :env (assoc (into {} (System/getenv)) "GIT_INDEX_FILE" alternate)}
                    "git" "read-tree" "HEAD")
        (spit (str (fs/path dir "a.txt")) "a2\n")
        (let [before (vec (fs/read-all-bytes (fs/path dir ".git" "index")))
              result (fix-stage/stage-files! dir [{:path "a.txt" :action :modify}]
                                             (assoc (into {} (System/getenv))
                                                    "GIT_INDEX_FILE" alternate))]
          (is (re-find #"already has staged changes" (str (:error result))))
          (is (= before (vec (fs/read-all-bytes (fs/path dir ".git" "index")))))
          (is (= "d.txt" (git! dir "diff" "--cached" "--name-only")))
          (is (not (fs/exists? (fs/path dir ".git" "index.lock")))))
        (finally (fs/delete-tree dir))))))

(deftest ^{:stratum 2} stage-files-stays-in-the-worktree-when-git-dir-names-another-repo-test
  (testing "GIT_DIR and GIT_WORK_TREE bound to another repository do not move the staging there"
    (let [dir   (repo-with-unrelated-change)
          other (repo-with-unrelated-change)]
      (try
        (spit (str (fs/path dir "a.txt")) "a2\n")
        (let [env    (assoc (into {} (System/getenv))
                            "GIT_DIR" (str (fs/path other ".git"))
                            "GIT_WORK_TREE" other)
              result (fix-stage/stage-files! dir [{:path "a.txt" :action :modify}] env)]
          (is (nil? (:error result)))
          (is (= (str (fs/canonicalize (fs/path dir ".git" "index")))
                 (str (fs/canonicalize (:index result)))))
          (is (fs/exists? (fs/path dir ".git" "index.lock")))
          (is (not (fs/exists? (fs/path other ".git" "index.lock"))))
          (fix-stage/discard-index! result))
        (finally
          (fs/delete-tree dir)
          (fs/delete-tree other))))))
