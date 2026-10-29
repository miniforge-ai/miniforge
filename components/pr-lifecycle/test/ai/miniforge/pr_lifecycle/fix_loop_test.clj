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
(ns ai.miniforge.pr-lifecycle.fix-loop-test
  "Unit tests for fix loop context building and prompt generation.

   Tests fix context creation, prompt building for each failure type,
   conversation follow-up routing, and worktree containment when a fix is
   applied to disk. Does not execute generation; git runs only to set up
   the temp repositories the worktree-containment tests write into."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.fix-loop :as fix]
   [ai.miniforge.pr-lifecycle.github-conversation :as conversation]
   [ai.miniforge.pr-lifecycle.policy-eval.git-dir :as git-dir]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Test Data
(defn- ^{:stratum 0} temp-layout
  "Create sibling `worktree` and `outside` dirs under a fresh temp dir,
   with `worktree` a git repository: the fix-path guard asks git where its
   metadata is and refuses every path when git cannot answer. Returns
   their paths as strings, plus `:base` for cleanup."
  []
  (let [base     (fs/create-temp-dir {:prefix "fix-loop-apply-"})
        worktree (str (fs/create-dirs (fs/path base "worktree")))]
    (process/sh {:dir worktree :env (git-dir/git-env)} "git" "init" "-q")
    {:base     (str base)
     :worktree worktree
     :outside  (str (fs/create-dirs (fs/path base "outside")))}))

(defn- ^{:stratum 0} git!
  "Run git in `dir` without the repository variables a git hook exports."
  [dir & args]
  (apply process/sh {:dir (str dir) :env (git-dir/git-env)} "git" args))

(defn- ^{:stratum 0} refused?
  "Apply a one-file fix writing (or deleting) `path` and return true when
   it is rejected as a path traversal."
  [worktree path action]
  (let [result (fix/apply-fix-to-worktree
                worktree {:code/files [{:path path :action action
                                        :content "#!/bin/sh\necho planted\n"}]} nil)]
    (and (dag/err? result)
         (= :fix-path-traversal (get-in result [:error :code])))))

(def ^{:stratum 0} test-task
  {:task/id (random-uuid)
   :task/title "Implement feature X"
   :task/acceptance-criteria ["Tests pass" "No lint errors"]
   :task/constraints ["No breaking changes"]})

(def ^{:stratum 0} test-pr-info
  {:pr/id 123
   :pr/url "https://github.com/org/repo/pull/123"
   :pr/branch "feat/feature-x"
   :pr/head-sha "abc123"})

;------------------------------------------------------------------------------ Prompt Building
(deftest ^{:stratum 0} build-fix-prompt-ci-failure-test
  (testing "CI failure prompt includes relevant info"
    (let [ctx {:fix/task-id (random-uuid)
               :fix/type :ci-failure
               :fix/failure-summary "2 test failures"
               :fix/affected-files #{"src/foo.clj"}
               :fix/failing-tests ["FAIL in (test-foo)" "FAIL in (test-bar)"]
               :fix/lint-errors ["src/foo.clj:10: warning"]
               :fix/build-errors []}
          prompt (fix/build-fix-prompt ctx)]
      (is (string? prompt))
      (is (str/includes? prompt "Fix CI failures"))
      (is (str/includes? prompt "2 test failures"))
      (is (str/includes? prompt "FAIL in (test-foo)"))
      (is (str/includes? prompt "warning")))))

(deftest ^{:stratum 0} build-fix-prompt-review-changes-test
  (testing "Review changes prompt includes requested changes"
    (let [ctx {:fix/task-id (random-uuid)
               :fix/type :review-changes
               :fix/failure-summary "1 change"
               :fix/affected-files #{}
               :fix/review-comments [{:file "src/a.clj" :line 10 :change "Fix null check"}]}
          prompt (fix/build-fix-prompt ctx)]
      (is (str/includes? prompt "Address review feedback"))
      (is (str/includes? prompt "Fix null check")))))

(deftest ^{:stratum 0} build-fix-prompt-conflict-test
  (testing "Conflict prompt mentions conflicting files"
    (let [ctx {:fix/task-id (random-uuid)
               :fix/type :conflict
               :fix/failure-summary "2 conflicts"
               :fix/affected-files #{"src/a.clj" "src/b.clj"}}
          prompt (fix/build-fix-prompt ctx)]
      (is (str/includes? prompt "Resolve merge conflicts"))
      (is (str/includes? prompt "src/a.clj")))))

(deftest ^{:stratum 0} build-fix-prompt-default-type-test
  (testing "Unknown fix type produces generic prompt"
    (let [ctx {:fix/task-id (random-uuid)
               :fix/type :unknown
               :fix/failure-summary "something broke"
               :fix/affected-files #{}}
          prompt (fix/build-fix-prompt ctx)]
      (is (str/includes? prompt "Fix issues"))
      (is (str/includes? prompt "something broke")))))

;------------------------------------------------------------------------------ Conversation Follow-up
(deftest ^{:stratum 0} resolve-comment-thread-skips-without-metadata-test
  (testing "Skips resolution when comment-id is missing"
    (let [ctx {:fix/comment-id nil :fix/parent-pr-number 123}
          result (fix/resolve-comment-thread "/tmp" ctx 456 nil)]
      (is (dag/ok? result))
      (is (true? (:skipped (:data result)))))))

(deftest ^{:stratum 0} resolve-comment-thread-replies-when-resolution-disabled-test
  (testing "Posts the fix link while leaving resolution disabled"
    (let [call (atom nil)]
      (with-redefs [conversation/link-fix-pr-to-comment
                    (fn [& args]
                      (reset! call args)
                      (dag/ok {:reply-posted true :resolved false}))]
        (let [ctx {:fix/comment-id 789 :fix/parent-pr-number 123}
              result (fix/resolve-comment-thread "/tmp" ctx 456 nil
                                                  :auto-resolve false)]
          (is (dag/ok? result))
          (is (= [:auto-resolve false] (take-last 2 @call))))))))

;------------------------------------------------------------------------------ Layer 1

;------------------------------------------------------------------------------ Fix Context Creation
(deftest ^{:stratum 1} create-fix-context-ci-failure-test
  (testing "CI failure context has correct structure"
    (let [failure {:type :ci-failure
                   :summary "2 test failures"
                   :test-failures ["FAIL in (test-foo)"]
                   :lint-errors []
                   :build-errors []}
          ctx (fix/create-fix-context test-task test-pr-info failure)]
      (is (= (:task/id test-task) (:fix/task-id ctx)))
      (is (= :ci-failure (:fix/type ctx)))
      (is (= test-pr-info (:fix/pr-info ctx)))
      (is (= "2 test failures" (:fix/failure-summary ctx)))
      (is (= ["FAIL in (test-foo)"] (:fix/failing-tests ctx)))
      (is (= ["Tests pass" "No lint errors"] (:fix/acceptance-criteria ctx)))
      (is (= ["No breaking changes"] (:fix/constraints ctx)))
      (is (vector? (:fix/previous-fixes ctx)))
      (is (inst? (:fix/created-at ctx))))))

(deftest ^{:stratum 1} create-fix-context-review-changes-test
  (testing "Review changes context includes comments"
    (let [comments [{:body "Fix the null check" :path "src/foo.clj"}]
          failure {:type :review-changes
                   :summary "1 requested change"
                   :comments comments}
          ctx (fix/create-fix-context test-task test-pr-info failure)]
      (is (= :review-changes (:fix/type ctx)))
      (is (= comments (:fix/review-comments ctx))))))

(deftest ^{:stratum 1} create-fix-context-conflict-test
  (testing "Conflict context includes conflict files"
    (let [failure {:type :conflict
                   :summary "2 files with conflicts"
                   :conflict-files ["src/a.clj" "src/b.clj"]}
          ctx (fix/create-fix-context test-task test-pr-info failure)]
      (is (= :conflict (:fix/type ctx)))
      (is (= ["src/a.clj" "src/b.clj"] (:fix/conflict-files ctx))))))

(deftest ^{:stratum 1} create-fix-context-with-previous-fixes-test
  (testing "Previous fixes are included in context"
    (let [failure {:type :ci-failure :summary "test failures"}
          prev [{:attempt 1 :error "first try failed"}]
          ctx (fix/create-fix-context test-task test-pr-info failure
                                       :previous-fixes prev)]
      (is (= prev (:fix/previous-fixes ctx))))))

(deftest ^{:stratum 1} create-fix-context-comment-metadata-test
  (testing "Comment metadata for conversation follow-up"
    (let [failure {:type :review-changes
                   :summary "1 change"
                   :comment-id 456
                   :parent-pr-number 100}
          ctx (fix/create-fix-context test-task test-pr-info failure)]
      (is (= 456 (:fix/comment-id ctx)))
      (is (= 100 (:fix/parent-pr-number ctx))))))

(deftest ^{:stratum 1} run-fix-loop-replies-when-resolution-disabled-test
  (testing "Successful fixes still post the review reply in reply-only mode"
    (let [follow-up-call (atom nil)
          failure {:type :review-changes
                   :summary "1 requested change"
                   :comment-id 456
                   :parent-pr-number 123}]
      (with-redefs [fix/generate-fix
                    (fn [& _] {:success true :artifact {} :metrics {}})
                    fix/apply-fix-to-worktree (fn [& _] (dag/ok {}))
                    fix/commit-fix (fn [& _] (dag/ok {:commit-sha "abc123"}))
                    fix/resolve-comment-thread
                    (fn [& args]
                      (reset! follow-up-call args)
                      (dag/ok {:reply-posted true :resolved false}))]
        (let [result (fix/run-fix-loop
                      test-task test-pr-info failure (fn [& _]) {}
                      :worktree-path "/tmp"
                      :auto-resolve-comments false)]
          (is (:success? result))
          (is (= [:auto-resolve false]
                 (take-last 2 @follow-up-call))))))))

;------------------------------------------------------------------------------ Fix Application
(deftest ^{:stratum 1} apply-fix-to-worktree-rejects-escaping-paths-test
  (let [{:keys [base worktree outside]} (temp-layout)
        victim (str (fs/path outside "victim.txt"))]
    (try
      (spit victim "keep")
      (testing "a ../ write is rejected and nothing lands outside"
        (let [result (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "../outside/pwned.txt"
                                              :content "x" :action :create}]} nil)]
          (is (dag/err? result))
          (is (= :fix-path-traversal (get-in result [:error :code])))
          (is (not (fs/exists? (fs/path outside "pwned.txt"))))))
      (testing "a write through an in-tree symlink to an outside dir is rejected"
        (fs/create-sym-link (fs/path worktree "link") outside)
        (let [result (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "link/pwned.txt"
                                              :content "x" :action :modify}]} nil)]
          (is (dag/err? result))
          (is (not (fs/exists? (fs/path outside "pwned.txt"))))))
      (testing "a write through a dangling in-tree symlink is rejected"
        (fs/create-sym-link (fs/path worktree "dangling") (fs/path outside "created.txt"))
        (let [result (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "dangling"
                                              :content "x" :action :modify}]} nil)]
          (is (dag/err? result))
          (is (not (fs/exists? (fs/path outside "created.txt"))))))
      (testing "a ../ delete is rejected and the outside file survives"
        (let [result (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "../outside/victim.txt"
                                              :action :delete}]} nil)]
          (is (dag/err? result))
          (is (fs/exists? victim))))
      (testing "a delete validates the entry it removes, not a final symlink's target"
        ;; worktree/link -> outside, and outside/back -> worktree/target.
        ;; Following the leaf resolves inside; the entry removed is outside/back.
        (spit (str (fs/path worktree "target.txt")) "in")
        (fs/create-sym-link (fs/path outside "back") (fs/path worktree "target.txt"))
        (let [result (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "link/back" :action :delete}]} nil)]
          (is (dag/err? result))
          (is (fs/exists? (fs/path outside "back") {:nofollow-links true}))))
      (finally (fs/delete-tree base)))))

(deftest ^{:stratum 1} apply-fix-to-worktree-is-all-or-nothing-test
  (testing "one escaping path rejects the whole artifact before any write"
    (let [{:keys [base worktree outside]} (temp-layout)]
      (try
        (let [result (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "ok.txt" :content "x" :action :create}
                                             {:path "../outside/pwned.txt"
                                              :content "x" :action :create}]} nil)]
          (is (dag/err? result))
          (is (= ["../outside/pwned.txt"] (get-in result [:error :data :paths])))
          (is (not (fs/exists? (fs/path worktree "ok.txt"))))
          (is (not (fs/exists? (fs/path outside "pwned.txt")))))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 1} apply-fix-to-worktree-refuses-every-symlink-test
  (let [{:keys [base worktree]} (temp-layout)
        target (str (fs/path worktree "docs" "a.md"))]
    (try
      (fs/create-dirs (fs/path worktree "docs"))
      (spit target "orig")
      (testing "a write through a link to an ordinary in-tree dir is refused"
        (fs/create-sym-link (fs/path worktree "docs-link") (fs/path worktree "docs"))
        (is (refused? worktree "docs-link/a.md" :modify))
        (is (= "orig" (slurp target))))
      (testing "deleting a link is refused, and the link and its target stay"
        (fs/create-sym-link (fs/path worktree "a-link.md") target)
        (is (refused? worktree "a-link.md" :delete))
        (is (fs/sym-link? (fs/path worktree "a-link.md")))
        (is (= "orig" (slurp target))))
      (testing "a dangling link as the last component is refused"
        (fs/create-sym-link (fs/path worktree "new.md") (fs/path worktree "docs" "new.md"))
        (is (refused? worktree "new.md" :create))
        (is (not (fs/exists? (fs/path worktree "docs" "new.md")))))
      (testing "a chain of directory links into .git is refused"
        (fs/create-sym-link (fs/path worktree "b") (fs/path worktree ".git"))
        (fs/create-sym-link (fs/path worktree "a") (fs/path worktree "b"))
        (is (refused? worktree "a/config" :modify)))
      (finally (fs/delete-tree base)))))

(deftest ^{:stratum 1} apply-fix-to-worktree-deletes-under-a-root-parent-test
  (testing "a delete whose parent resolves to the worktree root itself applies"
    (let [{:keys [base worktree]} (temp-layout)]
      (try
        (spit (str (fs/path worktree "a.txt")) "a")
        (spit (str (fs/path worktree "b.txt")) "b")
        (let [result (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "a.txt" :action :delete}
                                             {:path "././b.txt" :action :delete}]} nil)]
          (is (dag/ok? result))
          (is (not (fs/exists? (fs/path worktree "a.txt"))))
          (is (not (fs/exists? (fs/path worktree "b.txt")))))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 1} apply-fix-to-worktree-in-tree-changes-test
  (testing "create, modify and delete inside the worktree still apply"
    (let [{:keys [base worktree]} (temp-layout)]
      (try
        (fs/create-dirs (fs/path worktree "src"))
        (spit (str (fs/path worktree "src" "a.clj")) "old")
        (spit (str (fs/path worktree "gone.txt")) "bye")
        (let [result (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "src/a.clj" :content "new" :action :modify}
                                             {:path "src/b.clj" :content "b" :action :create}
                                             {:path "gone.txt" :action :delete}]} nil)]
          (is (dag/ok? result))
          (is (= 3 (get-in result [:data :files-modified])))
          (is (= "new" (slurp (str (fs/path worktree "src" "a.clj")))))
          (is (= "b" (slurp (str (fs/path worktree "src" "b.clj")))))
          (is (not (fs/exists? (fs/path worktree "gone.txt")))))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 1} apply-fix-to-worktree-refuses-the-git-dir-test
  (let [{:keys [base worktree]} (temp-layout)
        hook   (fs/path worktree ".git" "hooks" "pre-commit")
        config (str (fs/path worktree ".git" "config"))
        before (slurp config)]
    (try
      (fs/create-dirs (fs/path worktree ".github"))
      (testing "a direct write into .git/hooks is refused"
        (is (refused? worktree ".git/hooks/pre-commit" :create))
        (is (not (fs/exists? hook))))
      (testing "a ./x/../.git path is refused"
        (is (refused? worktree "./x/../.git/hooks/pre-commit" :create))
        (is (not (fs/exists? hook))))
      (testing "a ./.git path is refused"
        (is (refused? worktree "./.git/config" :modify))
        (is (= before (slurp config))))
      (testing "a write through a symlinked directory that points at .git is refused"
        (fs/create-sym-link (fs/path worktree "tools") (fs/path worktree ".git"))
        (is (refused? worktree "tools/hooks/pre-commit" :create))
        (is (not (fs/exists? hook))))
      (testing "a .GIT segment is refused, as case-insensitive file systems map it onto .git"
        (is (refused? worktree ".GIT/hooks/pre-commit" :create))
        (is (not (fs/exists? hook))))
      (testing "deleting .git or a file inside it is refused"
        (is (refused? worktree ".git/config" :delete))
        (is (refused? worktree ".git" :delete))
        (is (= before (slurp config))))
      (testing ".gitignore and .github paths still apply"
        (is (dag/ok? (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path ".gitignore" :content "target\n" :action :create}
                                             {:path ".github/CODEOWNERS" :content "* @o\n"
                                              :action :create}]} nil))))
      (finally (fs/delete-tree base)))))

(deftest ^{:stratum 1} apply-fix-to-worktree-refuses-an-outward-hooks-symlink-test
  (testing "with .git/hooks -> ../hooks, neither path to the active hook is writable"
    (let [{:keys [base worktree]} (temp-layout)
          hook (fs/path worktree "hooks" "pre-commit")]
      (try
        (fs/delete-tree (fs/path worktree ".git" "hooks"))
        (fs/create-dirs (fs/path worktree "hooks"))
        (spit (str hook) "#!/bin/sh\nexit 0\n")
        (fs/create-sym-link (fs/path worktree ".git" "hooks") (fs/path ".." "hooks"))
        (is (refused? worktree ".git/hooks/pre-commit" :modify))
        (is (refused? worktree "hooks/pre-commit" :modify))
        (is (refused? worktree "hooks/pre-commit" :delete))
        (is (= "#!/bin/sh\nexit 0\n" (slurp (str hook))))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 1} apply-fix-to-worktree-keeps-the-space-in-a-gitfile-target-test
  (testing "a gitdir: file naming `metadata ` protects that directory, trailing space included"
    (let [{:keys [base worktree]} (temp-layout)
          metadata (fs/path worktree "metadata ")
          config   (str (fs/path metadata "config"))]
      (try
        (fs/move (fs/path worktree ".git") metadata)
        (spit (str (fs/path worktree ".git")) "gitdir: metadata \n")
        (let [before (slurp config)]
          (is (refused? worktree "metadata /config" :modify))
          (is (refused? worktree "metadata /hooks/pre-commit" :create))
          (is (= before (slurp config))))
        (fs/create-dirs (fs/path worktree "metadata"))
        (is (dag/ok? (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "metadata/notes.txt" :content "x"
                                              :action :create}]} nil)))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 1} apply-fix-to-worktree-refuses-a-linked-worktree-common-dir-test
  (testing "a linked worktree's admin dir and the commondir it names are both refused"
    (let [{:keys [base worktree]} (temp-layout)
          common (fs/path worktree "modules" "common")
          admin  (fs/path worktree "modules" "sub")
          dot-git (str (fs/path worktree ".git"))]
      (try
        (fs/delete-tree dot-git)
        (git! base "init" "-q" "--bare" (str common))
        (fs/create-dirs admin)
        (spit (str (fs/path admin "HEAD")) "ref: refs/heads/wt\n")
        (spit (str (fs/path admin "commondir")) "../common\n")
        (spit (str (fs/path admin "gitdir")) (str dot-git "\n"))
        (spit dot-git "gitdir: modules/sub\n")
        (let [config (str (fs/path common "config"))
              before (slurp config)]
          (is (refused? worktree "modules/common/config" :modify))
          (is (refused? worktree "modules/common/hooks/pre-commit" :create))
          (is (refused? worktree "modules/sub/HEAD" :modify))
          (is (refused? worktree ".git" :modify))
          (is (refused? worktree ".git" :delete))
          (is (= before (slurp config)))
          (is (= "gitdir: modules/sub\n" (slurp dot-git))))
        (is (dag/ok? (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "modules/readme.md" :content "x"
                                              :action :create}]} nil)))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 1} apply-fix-to-worktree-refuses-the-core-hooks-path-test
  (testing "with core.hooksPath = .githooks, the tracked hook directory is refused"
    (let [{:keys [base worktree]} (temp-layout)
          hook (fs/path worktree ".githooks" "pre-commit")]
      (try
        (git! worktree "config" "core.hooksPath" ".githooks")
        (fs/create-dirs (fs/path worktree ".githooks"))
        (spit (str hook) "#!/bin/sh\nexit 0\n")
        (is (refused? worktree ".githooks/pre-commit" :modify))
        (is (refused? worktree ".githooks/post-checkout" :create))
        (is (refused? worktree ".githooks/pre-commit" :delete))
        (is (refused? worktree ".githooks" :delete))
        (is (= "#!/bin/sh\nexit 0\n" (slurp (str hook))))
        (is (not (fs/exists? (fs/path worktree ".githooks" "post-checkout"))))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 1} apply-fix-to-worktree-refuses-every-path-when-git-cannot-answer-test
  (testing "a .git file naming a missing directory leaves git unable to answer, so nothing is written"
    (let [{:keys [base worktree]} (temp-layout)]
      (try
        (fs/delete-tree (fs/path worktree ".git"))
        (spit (str (fs/path worktree ".git")) (str "gitdir: " (fs/path base "missing") "\n"))
        (is (refused? worktree "notes.txt" :create))
        (is (not (fs/exists? (fs/path worktree "notes.txt"))))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 1} apply-fix-to-worktree-compares-file-system-components-test
  (let [{:keys [base worktree]} (temp-layout)
        config (str (fs/path worktree ".git" "config"))
        before (slurp config)]
    (try
      (testing "a symlink named by a single space that points at .git is followed, not dropped"
        (fs/create-sym-link (fs/path worktree " ") (fs/path worktree ".git"))
        (is (refused? worktree " /config" :modify))
        (is (= before (slurp config))))
      (testing "on POSIX a backslash is part of a name, so link a\\b is one component"
        (fs/create-sym-link (fs/path worktree "a\\b") (fs/path worktree ".git"))
        (is (refused? worktree "a\\b/config" :modify))
        (is (= before (slurp config))))
      (finally (fs/delete-tree base)))))

(deftest ^{:stratum 1} apply-fix-to-worktree-protects-untracked-metadata-link-targets-test
  (let [{:keys [base worktree]} (temp-layout)
        hooks (fs/path worktree ".git" "hooks")
        hook  "#!/bin/sh\nexit 0\n"]
    (try
      (fs/create-dirs (fs/path worktree "scripts"))
      (spit (str (fs/path worktree "scripts" "pre-commit")) hook)
      (spit (str (fs/path worktree "scripts" "pre-push")) hook)
      (git! worktree "add" "scripts/pre-push")
      (fs/create-sym-link (fs/path hooks "pre-commit") (fs/path ".." ".." "scripts" "pre-commit"))
      (fs/create-sym-link (fs/path hooks "pre-push") (fs/path ".." ".." "scripts" "pre-push"))
      (testing "a hook linked to a script git does not track protects that script"
        (is (refused? worktree "scripts/pre-commit" :modify))
        (is (refused? worktree "scripts/pre-commit" :delete))
        (is (= hook (slurp (str (fs/path worktree "scripts" "pre-commit"))))))
      (testing "a hook linked to a tracked script leaves it editable; the edit shows in the diff"
        (is (dag/ok? (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "scripts/pre-push" :content "x"
                                              :action :modify}]} nil))))
      (testing "a symlinked .git/config protects its untracked target"
        (fs/move (fs/path worktree ".git" "config") (fs/path worktree "gitconfig"))
        (fs/create-sym-link (fs/path worktree ".git" "config") (fs/path ".." "gitconfig"))
        (is (refused? worktree "gitconfig" :modify)))
      (finally (fs/delete-tree base)))))

(deftest ^{:stratum 1} apply-fix-to-worktree-protects-untracked-refs-in-a-symlinked-refs-dir-test
  (testing "with .git/refs -> ../refs and only refs/README tracked, branch refs stay protected"
    (let [{:keys [base worktree]} (temp-layout)
          ref (str (fs/path worktree "refs" "heads" "work"))]
      (try
        (doseq [args [["config" "user.email" "t@example.com"] ["config" "user.name" "t"]
                      ["checkout" "-q" "-b" "work"]]]
          (apply git! worktree args))
        (fs/move (fs/path worktree ".git" "refs") (fs/path worktree "refs"))
        (fs/create-sym-link (fs/path worktree ".git" "refs") (fs/path ".." "refs"))
        (spit (str (fs/path worktree "refs" "README")) "refs
")
        (git! worktree "add" "refs/README")
        (git! worktree "commit" "-q" "-m" "init")
        (spit (str (fs/path worktree "refs" "NOTES")) "untracked\n")
        (let [sha (slurp ref)]
          (is (refused? worktree "refs/heads/work" :modify))
          (is (refused? worktree "refs/heads/work" :delete))
          (is (refused? worktree "refs/tags/v1" :create))
          (is (refused? worktree "refs/NOTES" :modify) "an untracked sibling of a tracked file")
          (is (= sha (slurp ref))))
        (testing "deleting even the tracked refs/README is refused: its directory is where .git/refs resolves"
          (is (refused? worktree "refs/README" :delete))
          (is (fs/exists? (fs/path worktree "refs" "README"))))
        (is (dag/ok? (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "refs/README" :content "x"
                                              :action :modify}]} nil)))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 1} apply-fix-to-worktree-protects-a-dangling-config-link-target-test
  (let [{:keys [base worktree]} (temp-layout)
        config (fs/path worktree ".git" "config")]
    (try
      (testing "with .git/config -> ../gitconfig missing, creating gitconfig is refused"
        (fs/delete config)
        (fs/create-sym-link config (fs/path ".." "gitconfig"))
        (is (refused? worktree "gitconfig" :create))
        (is (not (fs/exists? (fs/path worktree "gitconfig")))))
      (testing "with .git/config -> ../conf-a -> conf-b missing, neither link nor target is writable"
        (fs/delete config)
        (fs/create-sym-link config (fs/path ".." "conf-a"))
        (fs/create-sym-link (fs/path worktree "conf-a") "conf-b")
        (is (refused? worktree "conf-b" :create))
        (is (refused? worktree "conf-a" :modify))
        (is (not (fs/exists? (fs/path worktree "conf-b")))))
      (finally (fs/delete-tree base)))))

(deftest ^{:stratum 1} commit-fix-runs-git-with-hooks-disabled-test
  (testing "the fix loop's commit and push run no hook"
    (let [{:keys [base worktree]} (temp-layout)
          remote (str (fs/path base "remote.git"))
          ran    (str (fs/path base "hook-ran"))]
      (try
        (git! base "init" "-q" "--bare" remote)
        (doseq [args [["config" "user.email" "t@example.com"] ["config" "user.name" "t"]
                      ["remote" "add" "origin" remote] ["checkout" "-q" "-b" "fix/x"]]]
          (apply git! worktree args))
        (spit (str (fs/path worktree "a.txt")) "1\n")
        (git! worktree "add" "a.txt")
        (git! worktree "commit" "-q" "-m" "init")
        (doseq [hook ["pre-commit" "commit-msg" "post-commit" "reference-transaction"
                      "post-index-change" "pre-push"]]
          (let [f (fs/path worktree ".git" "hooks" hook)]
            (spit (str f) (str "#!/bin/sh\necho " hook " >> '" ran "'\n"))
            (fs/set-posix-file-permissions f "rwxr-xr-x")))
        (spit (str (fs/path worktree "a.txt")) "2\n")
        (let [result (fix/commit-fix worktree {:fix/type :review-changes
                                               :fix/affected-files ["a.txt"]}
                                     "fix/x" nil [{:path "a.txt" :action :modify}])]
          (is (dag/ok? result))
          (is (not (fs/exists? ran)))
          (is (= (get-in result [:data :commit-sha])
                 (str/trim (:out (git! remote "rev-parse" "fix/x"))))))
        (finally (fs/delete-tree base))))))
