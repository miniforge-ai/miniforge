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
(ns commit-budget-test
  "Pins `commit-budget`'s path-exclusion behaviour — which staged
   files count toward the commit-size budget and which are treated
   as bulk data. The gate runs on every commit; a silent widening
   (real code stops counting) or narrowing (fixture data starts
   blocking commits) of the exclusion set should fail here first.

   Also pins the merge-commit skip: mid-merge the staged diff carries
   the merged-in branch's lines, which are not the merging author's
   change and were budgeted on their own PRs."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(load-file "tasks/commit_budget.clj")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} excluded? (resolve 'commit-budget/excluded?))

(def ^{:stratum 0} file-reportable-count (resolve 'commit-budget/file-reportable-count))

(def ^{:stratum 0} merge-in-progress? (resolve 'commit-budget/merge-in-progress?))

(def ^{:stratum 0} staged-diff (resolve 'commit-budget/staged-diff))

(def ^{:stratum 0} check-commit-budget! (resolve 'commit-budget/check-commit-budget!))

(def ^{:stratum 0} override-rationale (resolve 'commit-budget/override-rationale))

(def ^{:stratum 0} default-budget @(resolve 'commit-budget/default-budget))

(defn ^{:stratum 0} ^:private unread-staged-diff
  "A `staged-diff` stand-in that fails if anything calls it.

   Mid-merge the gate has to return before reading the index at all,
   not merely ignore what it read: a large merge's diff should never be
   fetched or parsed, and `staged-diff` failing closed on a git error
   must not be able to block a merge the gate already declined to
   judge. Throwing also means a lost merge skip surfaces here as this
   error rather than as the gate's own `System/exit 1`, which would end
   the smoke run mid-suite and take every later namespace's result with
   it."
  []
  (throw (ex-info "staged-diff called while a merge was in progress" {})))

(def ^{:stratum 0} ^:private in-budget-entry
  "One small staged file — the ordinary, non-merge commit."
  {:path    "components/codex/src/core.clj"
   :added   ["(def a 1)" "(def b 2)"]
   :deleted []})

(defn ^{:stratum 0} ^:private delete-tree!
  "Recursively delete `f`. Depth-first via `file-seq` reversed, so
   children go before their parents."
  [f]
  (doseq [child (reverse (file-seq (io/file f)))]
    (.delete child)))

(defn ^{:stratum 0} ^:private hermetic-git-env
  "The process environment with every `GIT_*` variable dropped, then the
   scratch repo's own config scopes and commit identity put back.

   Dropping them is what makes the scratch repo actually separate. This
   test runs inside the pre-commit hook, and git exports `GIT_DIR` and
   `GIT_INDEX_FILE` to its hooks — inherited, those aim the fixture's
   commands at the real repository no matter what `:dir` says (`git
   add` fails with \"this operation must be run in a work tree\"; a
   command that didn't fail would be operating on the live checkout).
   Clearing the whole prefix also takes the developer's
   `GIT_CONFIG_*`, and pointing both config scopes at an empty file
   keeps their `core.hooksPath`, `commit.gpgsign` and `user.*` out. It
   is a real empty file rather than /dev/null so this holds on Windows
   too."
  [empty-config]
  (let [cfg (str empty-config)]
    (->> (System/getenv)
         (remove (fn [[k _]] (str/starts-with? k "GIT_")))
         (into {})
         (merge {"GIT_CONFIG_GLOBAL"   cfg
                 "GIT_CONFIG_SYSTEM"   cfg
                 "GIT_AUTHOR_NAME"     "budget-test"
                 "GIT_AUTHOR_EMAIL"    "budget-test@example.invalid"
                 "GIT_COMMITTER_NAME"  "budget-test"
                 "GIT_COMMITTER_EMAIL" "budget-test@example.invalid"}))))

(def ^{:stratum 0} ^:private merge-probe
  "A bb program that prints `merge-in-progress?` for its own working
   directory. `pr-str` on the path so a Windows path's backslashes
   survive as a Clojure string literal."
  (format "(load-file %s) (print (commit-budget/merge-in-progress?))"
          (pr-str (.getAbsolutePath (io/file "tasks/commit_budget.clj")))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ^:private git!
  "Run git in `dir` under `hermetic-git-env` and return the result.
   Throws on a non-zero exit — a fixture that has quietly stopped
   building what the test claims should say so, not leave the
   assertions running against nothing."
  [dir empty-config & args]
  (let [{:keys [exit err] :as res}
        (apply p/sh {:dir dir :out :string :err :string
                     :env (hermetic-git-env empty-config)}
               "git" args)]
    (when-not (zero? exit)
      (throw (ex-info "git fixture command failed"
                      {:args args :dir (str dir) :exit exit :err err})))
    res))

(defn ^{:stratum 1} ^:private merge-in-progress-at?
  "Run the production `merge-in-progress?` with `dir` as its working
   directory, in its own process under `hermetic-git-env`.

   A subprocess rather than a direct call because the function answers
   for the git context of the process it runs in — which is the point,
   and which in-process would be this test runner's, i.e. the real
   repository under the pre-commit hook. Going out to a process is also
   the only way to exercise the zero-arity form the hook actually
   calls, instead of a dir-taking variant that would exist only here."
  [dir empty-config]
  (let [{:keys [out err exit]}
        (p/sh {:dir dir :out :string :err :string
               :env (hermetic-git-env empty-config)}
              "bb" "-e" merge-probe)]
    (when-not (zero? exit)
      (throw (ex-info "merge probe failed"
                      {:dir (str dir) :exit exit :err err})))
    (= "true" (str/trim out))))

(defn ^{:stratum 1} ^:private gate-output
  "Stdout from one `check-commit-budget!` run over a stubbed merge state,
   `staged-diff`, and override rationale, so the gate's decision can be
   read without a real repo or index.

   `override-rationale` is stubbed to `rationale` (pass nil for the
   non-overridden path) rather than left reading the process
   environment: this test runs inside the pre-commit hook, where a
   developer legitimately using
   `MINIFORGE_COMMIT_BUDGET_OVERRIDE='<why>' git commit ...` would
   otherwise flip every gate assertion onto the override branch and
   fail the very commit the override was meant to let through."
  [merging? staged-diff-fn budget rationale]
  (with-redefs-fn {merge-in-progress? (constantly merging?)
                   staged-diff        staged-diff-fn
                   override-rationale (constantly rationale)}
    #(with-out-str (check-commit-budget! budget))))

(deftest ^{:stratum 1} resource-data-files-excluded-test
  (testing "data extensions under resources/ and messages/, nested or top-level"
    (is (excluded? "components/codex/resources/catalog.edn"))
    (is (excluded? "resources/catalog.json"))
    (is (excluded? "components/pr-lifecycle/resources/messages/en.edn"))
    (is (excluded? "seeds/initial.csv"))))

(deftest ^{:stratum 1} test-resources-dir-excluded-test
  (testing "test-resources/ is a fixture-data dir, same as resources/"
    (is (excluded? "components/codex/test-resources/notes/note-001.edn"))
    (is (excluded? "components/codex/test-resources/expected.json"))
    (is (excluded? "test-resources/sample.yaml"))))

(deftest ^{:stratum 1} suffixed-fixture-dirs-excluded-test
  (testing "dirs named *-fixture / *-fixtures match, not only the bare names"
    (is (excluded? "components/codex/test/codex-fixture/out.edn"))
    (is (excluded? "components/workflow/test/handoff-fixtures/phase.json"))
    (is (excluded? "test/fixtures/graph.edn"))
    (is (excluded? "test/fixture/graph.edn")))
  (testing "a dir merely *containing* 'fixtures' does not match"
    (is (not (excluded? "src/myfixtures/real_code.edn")))
    (is (not (excluded? "prefixtures/real_code.edn")))))

(deftest ^{:stratum 1} markdown-fixtures-excluded-test
  (testing ".md under fixture-data dirs is verbatim generator output"
    (is (excluded? "components/codex/test-resources/notes/note-001.md"))
    (is (excluded? "components/codex/test/codex-fixture/expected-note.md"))
    (is (excluded? "test/fixtures/rendered-report.md"))
    (is (excluded? "seeds/template.md")))
  (testing ".md elsewhere is reviewable prose and stays in budget"
    (is (not (excluded? "README.md")))
    (is (not (excluded? "docs/architecture/overview.md")))
    (is (not (excluded? "components/codex/resources/help.md")))
    (is (not (excluded? "components/pr-lifecycle/resources/messages/en/body.md")))))

(deftest ^{:stratum 1} code-shaped-files-stay-in-budget-test
  (testing "code-shaped EDN outside data dirs counts toward budget"
    (is (not (excluded? "deps.edn")))
    (is (not (excluded? "bb.edn")))
    (is (not (excluded? "workspace.edn")))
    (is (not (excluded? "components/codex/deps.edn")))
    (is (not (excluded? "work/n07-opsv-agent-budgets.spec.edn"))))
  (testing "behavior-as-data YAML/JSON outside data dirs counts"
    (is (not (excluded? ".github/workflows/ci.yml")))
    (is (not (excluded? "tsconfig.json"))))
  (testing "ordinary source files count"
    (is (not (excluded? "components/codex/src/ai/miniforge/codex/core.clj")))
    (is (not (excluded? "tasks/commit_budget.clj")))))

(deftest ^{:stratum 1} excluded-file-reports-zero-test
  (testing "an excluded fixture file contributes 0 reportable lines"
    (is (zero? (file-reportable-count
                {:path    "components/codex/test-resources/notes/note-001.md"
                 :added   ["# Note 001" "generated body line"]
                 :deleted []}))))
  (testing "the same content under src/ counts"
    (is (pos? (file-reportable-count
               {:path    "components/codex/src/note.md"
                :added   ["# Note 001" "generated body line"]
                :deleted []})))))

;------------------------------------------------------------------------------ Layer 2

;; Integration scenario — the one test here that touches the filesystem
;; and spawns git, because MERGE_HEAD detection only reproduces against
;; real merge state. Confined to its own scratch repo under
;; java.io.tmpdir and deleted afterwards; it never reads or writes the
;; checkout it runs from.
(deftest ^{:stratum 2} merge-detected-in-linked-worktree-test
  (testing "a merge left in progress is detected, and only in the worktree doing it"
    (let [tmp  (.toFile (java.nio.file.Files/createTempDirectory
                         "commit-budget-merge"
                         (into-array java.nio.file.attribute.FileAttribute [])))
          cfg  (io/file tmp "gitconfig")
          repo (io/file tmp "repo")
          ;; The merge happens in a LINKED worktree, where `.git` is a
          ;; file and MERGE_HEAD lives under `.git/worktrees/<name>/`.
          ;; A detector that stats `.git/MERGE_HEAD` passes in the main
          ;; checkout and fails here — and here is where this repo works.
          wt   (io/file tmp "wt")]
      (try
        (spit cfg "")
        (.mkdirs repo)
        (git! repo cfg "init" "-q" "-b" "main" ".")
        (spit (io/file repo "base.txt") "base\n")
        (git! repo cfg "add" "-A")
        (git! repo cfg "commit" "-qm" "base")
        (git! repo cfg "branch" "feature")
        (spit (io/file repo "from-main.txt") "main\n")
        (git! repo cfg "add" "-A")
        (git! repo cfg "commit" "-qm" "main work")
        (git! repo cfg "worktree" "add" "-q" (str wt) "feature")
        (spit (io/file wt "from-feature.txt") "feature\n")
        (git! wt cfg "add" "-A")
        (git! wt cfg "commit" "-qm" "feature work")
        ;; --no-commit is the non-conflicting way to leave MERGE_HEAD
        ;; set; a conflicted merge reaches the same state by a noisier
        ;; route. Both are how `git commit` — and so this gate — ends up
        ;; running mid-merge at all: a clean auto-committed merge fires
        ;; `pre-merge-commit`, not `pre-commit`.
        (git! wt cfg "merge" "--no-commit" "--no-ff" "main")
        (is (true? (merge-in-progress-at? wt cfg)))
        (is (false? (merge-in-progress-at? repo cfg)))
        (finally (delete-tree! tmp))))))

(deftest ^{:stratum 2} merge-skips-the-budget-test
  (testing "mid-merge the gate skips without reading the index at all"
    (let [out (gate-output true unread-staged-diff default-budget nil)]
      (is (str/includes? out "skipped (merge in progress)"))
      (is (not (str/includes? out "lines OK"))))))

(deftest ^{:stratum 2} non-merge-commit-still-budgeted-test
  (testing "outside a merge the gate still reports against the budget"
    (let [out (gate-output false (constantly [in-budget-entry]) default-budget nil)]
      (is (str/includes? out (format "%d / %d lines OK"
                                     (count (:added in-budget-entry))
                                     default-budget)))
      (is (not (str/includes? out "skipped"))))))

(deftest ^{:stratum 2} override-bypasses-budget-test
  (testing "with a rationale set the gate reports OVERRIDDEN and echoes it"
    (let [out (gate-output false (constantly [in-budget-entry]) default-budget
                           "generated migration")]
      (is (str/includes? out "OVERRIDDEN"))
      (is (str/includes? out "rationale: generated migration"))
      (is (not (str/includes? out "lines OK")))))
  (testing "over budget, the override still passes but warns"
    (let [out (gate-output false (constantly [in-budget-entry]) 1
                           "generated migration")]
      (is (str/includes? out "OVERRIDDEN"))
      (is (str/includes? out "budget intentionally bypassed")))))
