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
(ns ai.miniforge.release-executor.core-sandbox-test
  "Tests for environment-model dispatch in release-executor core.

   Verifies that execute-release-phase always routes through the executor
   (sandbox) path — stages dirty files instead of writing from code artifacts."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [clojure.string]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.release-executor.core :as core]))

;------------------------------------------------------------------------------ Layer 0

;; ============================================================================
;; Mock executor
;; ============================================================================
(defn ^{:stratum 0} create-mock-executor
  "Create a mock executor that succeeds for all operations."
  [& {:keys [responses default-response]
      :or {responses {}
           default-response {:exit-code 0 :stdout "" :stderr ""}}}]
  (let [commands (atom [])]
    [(reify
       dag/TaskExecutor
       (executor-type [_] :mock)
       (available? [_] (dag/ok {:available? true}))
       (acquire-environment! [_ _ _] (dag/ok {:environment-id "mock-env"}))
       (execute! [_ _env-id command _opts]
         (swap! commands conj command)
         (let [response (or (some (fn [[substr resp]]
                                    (when (clojure.string/includes? (str command) substr)
                                      resp))
                                  responses)
                            default-response)]
           (dag/ok response)))
       (copy-to! [_ _ _ _] (dag/ok {}))
       (copy-from! [_ _ _ _] (dag/ok {}))
       (release-environment! [_ _] (dag/ok {:released? true}))
       (environment-status [_ _] (dag/ok {:status :running})))
     commands]))

;; ============================================================================
;; Test helpers
;; ============================================================================
(defn ^{:stratum 0} make-workflow-state
  "Create a minimal workflow state."
  []
  {:workflow/artifacts []
   :workflow/spec {:spec/description "test task"}})

(def ^{:stratum 0} test-release-meta
  "Pre-built release metadata so tests don't need an LLM backend."
  {:release/id (random-uuid)
   :release/branch-name "feature/test-change"
   :release/commit-message "feat: test change"
   :release/pr-title "feat: test change"
   :release/pr-description "## Summary\nTest change"
   :release/created-at (java.util.Date.)})

;; ============================================================================
;; gh-exec-opts and governed-mode token injection
;; ============================================================================
(deftest ^{:stratum 0} gh-exec-opts-with-token-test
  (testing "gh-exec-opts injects GH_TOKEN env var when github-token present"
    (let [gh-exec-opts (var-get (ns-resolve 'ai.miniforge.release-executor.core 'gh-exec-opts))]
      (is (= {:env {"GH_TOKEN" "my-secret-token"}}
             (gh-exec-opts {:github-token "my-secret-token"}))))))

(deftest ^{:stratum 0} gh-exec-opts-without-token-test
  (testing "gh-exec-opts returns empty map when no github-token"
    (let [gh-exec-opts (var-get (ns-resolve 'ai.miniforge.release-executor.core 'gh-exec-opts))]
      (is (= {} (gh-exec-opts {})))
      (is (= {} (gh-exec-opts {:github-token nil}))))))

;; ============================================================================
;; Stacked-PR base: a chained DAG task's parent-branch override wins
;; ============================================================================
(def ^{:stratum 0} ^:private detects-main-branch
  "Mock executor responses so detect-default-branch resolves to 'main'."
  {"symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}})

;; ============================================================================
;; PR provenance frontmatter — deterministic PR → workflow + spec mapping
;; ============================================================================
(deftest ^{:stratum 0} provenance-frontmatter-renders-yaml
  (testing "frontmatter carries workflow + spec + task + commit + generated-by"
    (let [fm (core/provenance-frontmatter
              {:provenance {:workflow "adhoc--123" :spec "work/x.spec.edn" :task "t-1"}
               :commit-sha "abc1234"})]
      (is (clojure.string/starts-with? fm "---\n"))
      (is (clojure.string/includes? fm "miniforge-workflow: adhoc--123"))
      (is (clojure.string/includes? fm "spec: work/x.spec.edn"))
      (is (clojure.string/includes? fm "task: t-1"))
      (is (clojure.string/includes? fm "commit: abc1234"))
      (is (clojure.string/includes? fm "generated-by: miniforge"))
      (is (clojure.string/includes? fm "\n---\n\n")))))

(deftest ^{:stratum 0} provenance-frontmatter-omits-absent-fields
  (testing "absent spec/task/commit omitted; workflow + generated-by remain"
    (let [fm (core/provenance-frontmatter {:provenance {:workflow "run-1"} :commit-sha nil})]
      (is (clojure.string/includes? fm "miniforge-workflow: run-1"))
      (is (not (clojure.string/includes? fm "spec:")))
      (is (not (clojure.string/includes? fm "task:")))
      (is (not (clojure.string/includes? fm "commit:")))
      (is (clojure.string/includes? fm "generated-by: miniforge")))))

(deftest ^{:stratum 0} provenance-frontmatter-escapes-yaml-significant-characters
  (testing "a value carrying YAML-significant characters (: followed by space, #) is
            double-quoted rather than emitted as a raw plain scalar"
    (let [fm (core/provenance-frontmatter
              {:provenance {:workflow "adhoc--123"
                            :spec "work/x.spec.edn"
                            :task "fix: the thing # not a comment"}
               :commit-sha "abc1234"})]
      (is (clojure.string/includes? fm "task: \"fix: the thing # not a comment\""))
      (is (= 2 (count (re-seq #"(?m)^---$" fm)))
          "frontmatter still has exactly one opening and one closing delimiter"))))

(deftest ^{:stratum 0} provenance-frontmatter-escapes-leading-dash-and-newline
  (testing "a leading '-' or an embedded newline in a value is quoted/escaped, not emitted raw"
    (let [fm (core/provenance-frontmatter
              {:provenance {:workflow "-danger" :spec "line1\nline2"}
               :commit-sha nil})]
      (is (clojure.string/includes? fm "miniforge-workflow: \"-danger\""))
      (is (clojure.string/includes? fm "spec: \"line1\\nline2\""))
      (is (= 2 (count (re-seq #"(?m)^---$" fm)))
          "an embedded newline must not introduce a spurious frontmatter delimiter line"))))

(deftest ^{:stratum 0} provenance-frontmatter-escapes-crlf-and-lone-cr
  (testing "a Windows \\r\\n or a lone \\r doesn't slip through as a raw control character"
    (let [fm (core/provenance-frontmatter
              {:provenance {:workflow "line1\r\nline2" :spec "line1\rline2"}
               :commit-sha nil})]
      (is (clojure.string/includes? fm "miniforge-workflow: \"line1\\nline2\"")
          "\\r\\n collapses to a single \\n escape, not two")
      (is (clojure.string/includes? fm "spec: \"line1\\nline2\"")
          "a lone \\r is normalized to \\n, not left as a raw control character")
      (is (not (clojure.string/includes? fm "\r")) "no raw carriage return survives into the frontmatter")
      (is (= 2 (count (re-seq #"(?m)^---$" fm)))))))

(deftest ^{:stratum 0} provenance-frontmatter-force-quotes-yaml-reserved-words
  (testing "a value that would otherwise pass the safe-character-set check but reads
            back as a YAML boolean/null/number (true/false/yes/no/null/~, or a bare
            number) is force-quoted so it round-trips as a string"
    (let [fm (core/provenance-frontmatter
              {:provenance {:workflow "true" :spec "123" :task "NO"}
               :commit-sha "false"})]
      (is (clojure.string/includes? fm "miniforge-workflow: \"true\""))
      (is (clojure.string/includes? fm "spec: \"123\""))
      (is (clojure.string/includes? fm "task: \"NO\"")
          "case-insensitive: YAML 1.1 parsers also read NO/Yes/etc. as booleans")
      (is (clojure.string/includes? fm "commit: \"false\""))
      (is (= 2 (count (re-seq #"(?m)^---$" fm)))))))

(deftest ^{:stratum 0} with-provenance-prepends-and-is-idempotent
  (testing "prepends frontmatter to a plain body"
    (let [out (core/with-provenance "## Summary\nbody" {:provenance {:workflow "r1"} :commit-sha "c1"})]
      (is (clojure.string/starts-with? out "---\n"))
      (is (clojure.string/includes? out "## Summary"))))
  (testing "does not double-prepend when the body already opens with frontmatter"
    (let [already "---\nminiforge-workflow: x\n---\n\nbody"]
      (is (= already (core/with-provenance already {:provenance {:workflow "r1"}}))))))

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; Environment model: executor always required
;; ============================================================================
(deftest ^{:stratum 1} executor-required-for-release
  (testing "execute-release-phase fails without :executor in context"
    (let [result (core/execute-release-phase
                  (make-workflow-state)
                  {:worktree-path "/tmp/wt" :environment-id "env-1" :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (not (:success? result)))
      (is (= :missing-executor (:type (first (:errors result))))))))

(deftest ^{:stratum 1} environment-id-required-for-release
  (testing "execute-release-phase fails without :environment-id in context"
    (let [[exec _] (create-mock-executor)
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:worktree-path "/tmp/wt" :executor exec :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (not (:success? result)))
      (is (= :missing-environment-id (:type (first (:errors result))))))))

(deftest ^{:stratum 1} worktree-path-required-for-release
  (testing "execute-release-phase fails without :worktree-path in context"
    (let [[exec _] (create-mock-executor)
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:executor exec :environment-id "env-1" :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (not (:success? result)))
      (is (= :missing-worktree-path (:type (first (:errors result))))))))

;; ============================================================================
;; Executor dispatch: git add instead of base64 write
;; ============================================================================
(deftest ^{:stratum 1} release-stages-dirty-files-not-base64
  (testing "release executor stages dirty files — no base64 file writes"
    (let [[exec cmds] (create-mock-executor
                       :responses {"gh auth"        {:exit-code 0 :stdout "Logged in" :stderr ""}
                                   "git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                   "git fetch"       {:exit-code 0 :stdout "" :stderr ""}
                                   "git checkout"    {:exit-code 0 :stdout "" :stderr ""}
                                   "git add"         {:exit-code 0 :stdout "" :stderr ""}
                                   "git diff"        {:exit-code 0 :stdout "src/a.clj\n" :stderr ""}
                                   "git commit"      {:exit-code 0 :stdout "" :stderr ""}
                                   "git rev-parse"   {:exit-code 0 :stdout "abc123\n" :stderr ""}})
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:executor exec
                   :environment-id "mock-env"
                   :worktree-path "/tmp/wt"
                   :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (:success? result))
      ;; git add . was sent to executor
      (is (some #(clojure.string/includes? % "git add") @cmds))
      ;; No base64 writes — files already in worktree
      (is (not (some #(clojure.string/includes? % "base64 -d") @cmds))))))

(deftest ^{:stratum 1} release-fails-when-nothing-staged
  (testing "release fails when git diff --cached shows nothing staged AND no commits ahead of base"
    (let [[exec _] (create-mock-executor
                    :responses {"git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                "git fetch"        {:exit-code 0 :stdout "" :stderr ""}
                                "git checkout"     {:exit-code 0 :stdout "" :stderr ""}
                                "git add"          {:exit-code 0 :stdout "" :stderr ""}
                                "git diff"         {:exit-code 0 :stdout "" :stderr ""} ;; empty — nothing staged
                                "git rev-list"     {:exit-code 0 :stdout "0\n" :stderr ""}}) ;; no commits ahead
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:executor exec
                   :environment-id "mock-env"
                   :worktree-path "/tmp/wt"
                   :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (not (:success? result)))
      (is (= :no-files-to-stage (:type (first (:errors result))))))))

(deftest ^{:stratum 1} release-succeeds-when-boundary-commits-already-on-branch-test
  (testing "Run-8 dogfood regression (2026-05-10): implementer writes were
            committed at the implement-phase boundary, leaving the worktree
            clean by the time release runs. step-create-branch now branches
            off HEAD (carrying the boundary commits), step-stage-dirty-files
            accepts an empty stage when the branch is already ahead of base,
            and step-commit skips creating a new commit (the existing HEAD
            becomes the release commit)."
    (let [[exec cmds] (create-mock-executor
                       :responses {"gh auth"          {:exit-code 0 :stdout "Logged in" :stderr ""}
                                   "git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                   "git fetch"        {:exit-code 0 :stdout "" :stderr ""}
                                   "git checkout"     {:exit-code 0 :stdout "" :stderr ""}
                                   "git add"          {:exit-code 0 :stdout "" :stderr ""}
                                   "git diff"         {:exit-code 0 :stdout "" :stderr ""} ;; nothing dirty
                                   "git rev-list"     {:exit-code 0 :stdout "4\n" :stderr ""} ;; 4 boundary commits ahead
                                   "git rev-parse"    {:exit-code 0 :stdout "deadbeef\n" :stderr ""}})
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:executor exec
                   :environment-id "mock-env"
                   :worktree-path "/tmp/wt"
                   :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (:success? result)
          "release must succeed when boundary commits already carry the work")
      (is (some #(clojure.string/includes? % "git rev-list --count --right-only origin/main...HEAD") @cmds)
          "release should consult the three-dot, right-only count so concurrent
           movement of origin/<base> doesn't artificially shrink the count")
      (is (not-any? #(clojure.string/starts-with? % "git commit") @cmds)
          "step-commit must NOT issue a new commit when the boundary commits already exist on the branch")
      (is (some #(clojure.string/includes? % "git diff origin/main...HEAD --numstat") @cmds)
          "step-validate-diff must inspect the merge-base range diff
           (origin/<base>...HEAD, three-dot), not the empty staged diff —
           boundary commits otherwise bypass the destructive-diff gate
           entirely. Three-dot form is critical: two-dot would compare to
           the *current* origin/<base>, which can have moved while the
           workflow was running, surfacing concurrent-merge files as
           spurious deletions."))))

(deftest ^{:stratum 1} release-rejects-destructive-boundary-commits-test
  (testing "step-validate-diff still rejects a destructive release when the
            destructive content lives in boundary commits rather than the
            staged index. Covers the case where the implementer's commits
            net-delete tests; without range-diff validation the destructive-
            diff gate would no-op silently."
    (let [destructive-numstat "0\t40\tcomponents/foo/test/foo_test.clj\n"
          destructive-diff    "diff --git a/components/foo/test/foo_test.clj b/components/foo/test/foo_test.clj\n-(deftest a)\n-(deftest b)\n-(deftest c)\n"
          [exec _] (create-mock-executor
                    :responses {"gh auth"          {:exit-code 0 :stdout "Logged in" :stderr ""}
                                "git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                "git fetch"        {:exit-code 0 :stdout "" :stderr ""}
                                "git checkout"     {:exit-code 0 :stdout "" :stderr ""}
                                "git add"          {:exit-code 0 :stdout "" :stderr ""}
                                "git diff --cached"  {:exit-code 0 :stdout "" :stderr ""}
                                "git diff origin/main...HEAD --numstat" {:exit-code 0 :stdout destructive-numstat :stderr ""}
                                "git diff origin/main...HEAD -U0"       {:exit-code 0 :stdout destructive-diff    :stderr ""}
                                "git rev-list"     {:exit-code 0 :stdout "1\n" :stderr ""}
                                "git rev-parse"    {:exit-code 0 :stdout "deadbeef\n" :stderr ""}})
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:executor exec
                   :environment-id "mock-env"
                   :worktree-path "/tmp/wt"
                   :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (not (:success? result))
          "release must fail when range diff shows net-negative tests, even in boundary-commits mode")
      (is (= :destructive-diff (:type (first (:errors result))))))))

(deftest ^{:stratum 1} execute-release-passes-github-token-test
  (testing "execute-release-phase threads github-token from context into state"
    (let [[exec _] (create-mock-executor
                    :responses {"git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                "git fetch"        {:exit-code 0 :stdout "" :stderr ""}
                                "git checkout"     {:exit-code 0 :stdout "" :stderr ""}
                                "git add"          {:exit-code 0 :stdout "" :stderr ""}
                                "git diff"         {:exit-code 0 :stdout "M src/a.clj" :stderr ""}
                                "git commit"       {:exit-code 0 :stdout "" :stderr ""}
                                "gh auth"          {:exit-code 0 :stdout "Logged in" :stderr ""}
                                "git push"         {:exit-code 0 :stdout "" :stderr ""}
                                "gh pr create"     {:exit-code 0 :stdout "https://github.com/o/r/pull/1" :stderr ""}
                                "git rev-parse"    {:exit-code 0 :stdout "abc123\n" :stderr ""}})
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:executor exec
                   :environment-id "mock-env"
                   :worktree-path "/tmp/wt"
                   :create-pr? true
                   :github-token "governed-token"}
                  {:release-meta test-release-meta})]
      ;; The pipeline should succeed and create a PR
      (is (:success? result)))))

(deftest ^{:stratum 1} step-create-branch-prefers-base-branch-override
  (testing "an explicit base-branch-override (chained DAG task's parent branch)
            wins over the executor-detected default, so the PR stacks on the
            parent and diff/commits-ahead range off it"
    (let [[exec _] (create-mock-executor :responses detects-main-branch)
          state    {:executor exec :environment-id "mock-env"
                    :release-meta test-release-meta
                    :base-branch-override "mf/parent-task"}
          result   (core/step-create-branch state)]
      (is (= "mf/parent-task" (:base-branch result))
          "override wins over the detected 'main'")))

  (testing "absent override → executor-detected default branch (root task / non-DAG)"
    (let [[exec _] (create-mock-executor :responses detects-main-branch)
          state    {:executor exec :environment-id "mock-env"
                    :release-meta test-release-meta}
          result   (core/step-create-branch state)]
      (is (= "main" (:base-branch result))))))

(deftest ^{:stratum 1} step-create-branch-fetches-override-branch
  (testing "when stacking on a parent branch, the override branch is fetched
            (create-branch! only fetched the default) so later origin/<base>
            range diffs and the PR merge-base resolve"
    (let [[exec commands] (create-mock-executor :responses detects-main-branch)
          state    {:executor exec :environment-id "mock-env"
                    :release-meta test-release-meta
                    :base-branch-override "mf/parent-task"}
          result   (core/step-create-branch state)]
      (is (not (core/failed? result)))
      (is (= "mf/parent-task" (:base-branch result)))
      (is (some #(clojure.string/includes? (str %) "fetch origin mf/parent-task") @commands)
          "the parent branch was fetched"))))

(deftest ^{:stratum 1} step-create-branch-degrades-when-override-fetch-fails
  (testing "an unfetchable parent branch degrades gracefully — the release
            does NOT fail; the PR targets the detected default instead of
            stacking (degraded, not fatal)"
    (let [[exec _] (create-mock-executor
                    :responses (merge detects-main-branch
                                      {"fetch origin mf/parent-task"
                                       {:exit-code 1 :stdout "" :stderr "no such ref"}}))
          state    {:executor exec :environment-id "mock-env"
                    :release-meta test-release-meta
                    :base-branch-override "mf/parent-task"}
          result   (core/step-create-branch state)]
      (is (not (core/failed? result)) "release proceeds despite the fetch failure")
      (is (= "main" (:base-branch result)) "falls back to the detected default"))))
