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

(deftest ^{:stratum 1} apply-fix-to-worktree-resolves-each-op-in-order-test
  (testing "deleting a symlink and recreating its name writes a new file, not the old target"
    (let [{:keys [base worktree]} (temp-layout)
          target (str (fs/path worktree "target.txt"))
          link   (fs/path worktree "link.txt")]
      (try
        (spit target "orig")
        (fs/create-sym-link link target)
        (let [result (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "link.txt" :action :delete}
                                             {:path "link.txt" :content "new" :action :create}]} nil)]
          (is (dag/ok? result))
          (is (= "orig" (slurp target)))
          (is (not (fs/sym-link? link)))
          (is (= "new" (slurp (str link)))))
        (finally (fs/delete-tree base))))))

(deftest ^{:stratum 1} apply-fix-to-worktree-deletes-under-a-root-parent-test
  (testing "a delete whose parent resolves to the worktree root itself applies"
    (let [{:keys [base worktree]} (temp-layout)]
      (try
        (spit (str (fs/path worktree "a.txt")) "a")
        (spit (str (fs/path worktree "b.txt")) "b")
        (fs/create-sym-link (fs/path worktree "self") worktree)
        (let [result (fix/apply-fix-to-worktree
                      worktree {:code/files [{:path "self/a.txt" :action :delete}
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
