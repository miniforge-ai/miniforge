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

(ns ai.miniforge.workflow.merge-parent-branches-integration-test
  "Integration tests for the v2 `merge-parent-branches!` orchestrator
   helper. Each test sets up a real git repo in a temp directory, runs
   real `git` commands, and asserts on the resulting refs / anomalies.
   Slow-ish but high-fidelity — these test the actual git behavior the
   spec depends on (ort vs octopus, ancestor collapse, unrelated
   histories, conflict surfacing)."
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Fixture: temp git repo

(def ^:dynamic *repo* nil)

(defn- run-git!
  "Run a git command in `cwd`. Throws on non-zero exit so test setup
   bugs surface immediately rather than as cryptic downstream failures.
   The throw is dev-internal — it never reaches a user — but the
   message is still routed through the workflow message catalog
   (system-locale entries) so we have one place to audit / change
   error wording."
  [cwd & args]
  (let [r (apply shell/sh "git" "-C" cwd args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (messages/t :dag.merge.system/git-test-failure
                                  {:args (str/join " " args)
                                   :err  (:err r)})
                      {:cwd cwd :args args :result r})))
    r))

(defn- write-file! [cwd path content]
  (let [f (java.io.File. ^String cwd ^String path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- commit-file! [cwd path content message]
  (write-file! cwd path content)
  (run-git! cwd "add" path)
  (run-git! cwd "commit" "-m" message))

(defn temp-repo-fixture [f]
  (let [repo (str (fs/create-temp-dir {:prefix "mpb-test-"}))]
    (try
      (run-git! repo "init" "-b" "main")
      (run-git! repo "config" "user.email" "test@miniforge.ai")
      (run-git! repo "config" "user.name" "miniforge-test")
      ;; Defeat any global signing config (1Password, GPG agents, etc.)
      ;; that would otherwise prompt or fail during commits in this
      ;; ephemeral repo. Mirrors the orchestrator's --no-gpg-sign flag
      ;; per spec §3.1: tests run hermetically regardless of dev-machine
      ;; signing setup.
      (run-git! repo "config" "commit.gpgsign" "false")
      (run-git! repo "config" "tag.gpgsign" "false")
      (commit-file! repo "README.md" "initial\n" "init")
      (binding [*repo* repo]
        (f))
      (finally
        (try (fs/delete-tree repo) (catch Throwable _ nil))))))

(use-fixtures :each temp-repo-fixture)

;------------------------------------------------------------------------------ Helpers

(defn- ctx-with-registry
  "Build a context map shaped like the orchestrator's runtime context.
   Registers parents in the registry so resolve-multi-parent-base sees
   them, and points host-repo-path at *repo*."
  [parent-entries]
  {:execution/repo-path *repo*
   :execution/worktree-path *repo*
   :workflow-id "test-run"
   :dag/branch-registry (atom (reduce-kv dag/register-branch
                                         (dag/create-branch-registry)
                                         parent-entries))})

(defn- create-parent-branch!
  "Branch off main, commit a file, return to main. Used to set up
   parent branches with disjoint changes."
  [branch-name path content]
  (run-git! *repo* "checkout" "-b" branch-name "main")
  (commit-file! *repo* path content (str "edit on " branch-name))
  (run-git! *repo* "checkout" "main"))

;------------------------------------------------------------------------------ Tests: 2-parent happy path

(deftest two-parent-disjoint-files-happy-path-test
  (testing "Two parents touching disjoint files merge cleanly via -s ort.
            The result is a real merge commit; the namespaced ref points
            at it; the orchestrator returns dag/ok wrapping {:branch
            :commit-sha ...}."
    (create-parent-branch! "task-a" "src/a.txt" "from a\n")
    (create-parent-branch! "task-b" "src/b.txt" "from b\n")
    (let [task-id "task-c"
          task-def {:task/id task-id
                    :task/deps [:a :b]}
          ctx (ctx-with-registry {:a {:branch "task-a"}
                                  :b {:branch "task-b"}})
          result (dag-orch/merge-parent-branches! ctx task-def)
          data (:data result)]
      (is (dag/ok? result))
      (is (str/starts-with? (:branch data) "refs/miniforge/dag-base/test-run/"))
      (is (string? (:commit-sha data)))
      (is (= 40 (count (:commit-sha data)))
          "commit SHA is full 40-char hex (rev-parse default)")
      ;; The merge commit should have two parents
      (let [parents-cmd (run-git! *repo* "rev-list" "--parents" "-n" "1"
                                  (:commit-sha data))
            parts (str/split (str/trim (:out parents-cmd)) #"\s+")]
        (is (= 3 (count parts))
            "merge commit + 2 parents = 3 SHAs"))
      ;; Both files should be present in the merged tree
      (let [tree (run-git! *repo* "ls-tree" "-r" "--name-only"
                           (:commit-sha data))
            files (set (str/split-lines (str/trim (:out tree))))]
        (is (contains? files "src/a.txt"))
        (is (contains? files "src/b.txt"))))))

;------------------------------------------------------------------------------ Tests: ancestor collapse

(deftest ancestor-collapses-to-single-parent-test
  (testing "When parent A is an ancestor of parent B, A's contributions
            are already in B. The collapse algorithm drops A and
            returns single-parent fast path against B — no merge commit
            needed, the existing branch is the right base."
    ;; task-a is an ancestor of task-b (b is built on top of a)
    (run-git! *repo* "checkout" "-b" "task-a" "main")
    (commit-file! *repo* "src/a.txt" "from a\n" "edit on a")
    (run-git! *repo* "checkout" "-b" "task-b" "task-a")
    (commit-file! *repo* "src/b.txt" "from b\n" "edit on b")
    (run-git! *repo* "checkout" "main")
    (let [task-def {:task/id "task-c" :task/deps [:a :b]}
          ctx (ctx-with-registry {:a {:branch "task-a"}
                                  :b {:branch "task-b"}})
          result (dag-orch/merge-parent-branches! ctx task-def)
          data (:data result)]
      (is (dag/ok? result))
      (is (:single-parent? data)
          "after ancestor collapse, only task-b remains as effective parent")
      (is (= "task-b" (:branch data))
          "the surviving parent's branch is returned directly")
      (is (some #(= :a (:dropped %)) (:collapsed data))
          ":collapsed records what was absorbed")
      (is (some #(= :b (:absorbed-into %)) (:collapsed data))
          "and which surviving parent absorbed it"))))

;------------------------------------------------------------------------------ Tests: duplicate parent tips

(deftest duplicate-tips-collapse-test
  (testing "When two declared parents resolve to the same SHA (e.g.
            both branches advanced to the same commit), the duplicate
            collapses and the merge becomes single-parent."
    (run-git! *repo* "checkout" "-b" "task-a" "main")
    (commit-file! *repo* "src/x.txt" "shared\n" "shared edit")
    ;; task-a-alias points at exactly the same commit as task-a
    (run-git! *repo* "branch" "task-a-alias" "task-a")
    (run-git! *repo* "checkout" "main")
    (let [task-def {:task/id "task-c" :task/deps [:a :alias]}
          ctx (ctx-with-registry {:a     {:branch "task-a"}
                                  :alias {:branch "task-a-alias"}})
          result (dag-orch/merge-parent-branches! ctx task-def)]
      (is (dag/ok? result))
      (is (:single-parent? (:data result))
          "duplicate tips collapse to one effective parent"))))

;------------------------------------------------------------------------------ Tests: unrelated histories

(deftest unrelated-histories-anomaly-test
  (testing "When parents share no common ancestor (e.g. one came from a
            separate `git init`), v2 surfaces a typed anomaly rather than
            using `--allow-unrelated-histories`. This protects against
            accidental cross-repo / stray-init situations."
    (create-parent-branch! "task-a" "src/a.txt" "from a\n")
    ;; Build task-b on a synthetic root commit unrelated to main
    (run-git! *repo* "checkout" "--orphan" "task-b")
    (run-git! *repo* "rm" "-rf" ".")
    (commit-file! *repo* "src/b.txt" "from b — unrelated\n" "unrelated root")
    (run-git! *repo* "checkout" "main")
    (let [task-def {:task/id "task-c" :task/deps [:a :b]}
          ctx (ctx-with-registry {:a {:branch "task-a"}
                                  :b {:branch "task-b"}})
          result (dag-orch/merge-parent-branches! ctx task-def)]
      (is (= :anomalies/dag-multi-parent-unrelated-histories
             (:anomaly/category result))))))

;------------------------------------------------------------------------------ Tests: conflict

(deftest conflict-surfaces-typed-anomaly-test
  (testing "When two parents touch the same file with different content,
            the merge conflicts. v2 (Stage 1B) surfaces the conflict as
            a typed anomaly carrying the parent SHAs, conflict paths,
            and raw git stderr. Stage 2 will replace this with the
            resolution sub-workflow; for 1B the task fails."
    (run-git! *repo* "checkout" "-b" "task-a" "main")
    (commit-file! *repo* "src/conflict.txt" "from a\n" "a edit")
    (run-git! *repo* "checkout" "-b" "task-b" "main")
    (commit-file! *repo* "src/conflict.txt" "from b\n" "b edit")
    (run-git! *repo* "checkout" "main")
    (let [task-def {:task/id "task-c" :task/deps [:a :b]}
          ctx (ctx-with-registry {:a {:branch "task-a"}
                                  :b {:branch "task-b"}})
          result (dag-orch/merge-parent-branches! ctx task-def)]
      (is (= :anomalies/dag-multi-parent-conflict
             (:anomaly/category result)))
      (is (some #(= "src/conflict.txt" (:path %))
                (:merge/conflicts result))
          "the conflicting path is enumerated for the resolution agent
           (Stage 2) to consume"))))

;------------------------------------------------------------------------------ Tests: branch unresolvable

(deftest unregistered-branch-falls-back-to-default-test
  (testing "When no parents are registered (orchestrator state shouldn't
            allow this in production but defensive code is correct
            anyway), merge-parent-branches! falls back to the spec
            default branch."
    (let [task-def {:task/id "task-c" :task/deps [:a :b]}
          ;; Empty registry context
          ctx {:execution/repo-path *repo*
               :execution/opts {:branch "main"}
               :workflow-id "test-run"
               :dag/branch-registry (atom (dag/create-branch-registry))}
          result (dag-orch/merge-parent-branches! ctx task-def)
          data (:data result)]
      (is (dag/ok? result))
      (is (:single-parent? data))
      (is (= :no-registered-parents (:fallback-reason data))))))

(deftest registered-branch-not-in-repo-anomaly-test
  (testing "When a registered branch doesn't exist in the host repo
            (the registry is out of sync with reality), v2 surfaces a
            typed branch-unresolvable anomaly instead of crashing."
    (let [task-def {:task/id "task-c" :task/deps [:a :b]}
          ctx (ctx-with-registry {:a {:branch "task-a-does-not-exist"}
                                  :b {:branch "task-b-does-not-exist"}})
          result (dag-orch/merge-parent-branches! ctx task-def)]
      (is (= :anomalies/dag-multi-parent-branch-unresolvable
             (:anomaly/category result))))))

;------------------------------------------------------------------------------ Tests: idempotency cache

(deftest idempotency-second-call-reuses-merge-ref-test
  (testing "Spec §7.2 idempotency: a second call to merge-parent-branches!
            with the same effective inputs reuses the existing namespaced
            ref instead of producing a fresh merge commit. Without the
            cache check, merge commit timestamps would differ on every
            call — same tree, different SHA — defeating the spec's
            'replays of the same effective input reuse the same ref'
            guarantee."
    (create-parent-branch! "task-a" "src/a.txt" "from a\n")
    (create-parent-branch! "task-b" "src/b.txt" "from b\n")
    (let [task-def {:task/id "task-c" :task/deps [:a :b]}
          ctx (ctx-with-registry {:a {:branch "task-a"}
                                  :b {:branch "task-b"}})
          first-result  (dag-orch/merge-parent-branches! ctx task-def)
          second-result (dag-orch/merge-parent-branches! ctx task-def)
          first-data    (:data first-result)
          second-data   (:data second-result)]
      (is (dag/ok? first-result))
      (is (dag/ok? second-result))
      (is (= (:branch first-data) (:branch second-data))
          "same ref name (input-key is deterministic)")
      (is (= (:commit-sha first-data) (:commit-sha second-data))
          "same commit SHA — second call hit the cache, did NOT produce a
           fresh merge commit. This is the property that justifies the
           input-key derivation in spec §3.2 step 6.")
      (is (true? (:cache-hit? second-data))
          "cache-hit flag exposed for observability — dashboard can show
           how often replays are reusing existing merge work")
      (is (false? (:cache-hit? first-data))
          "first call sets up the cache; cache-hit? is false there"))))

;------------------------------------------------------------------------------ Tests: unsupported strategy

(deftest unsupported-strategy-anomaly-test
  (testing "Strategies other than :git-merge fail fast with a typed
            anomaly rather than silently falling through. :sequential-merge
            ships in Stage 4; until then plans that explicitly request it
            should see the unsupported-strategy anomaly so they don't
            silently get :git-merge behavior under a different label."
    (create-parent-branch! "task-a" "src/a.txt" "from a\n")
    (create-parent-branch! "task-b" "src/b.txt" "from b\n")
    (let [task-def {:task/id "task-c"
                    :task/deps [:a :b]
                    :task/merge-strategy :sequential-merge}
          ctx (ctx-with-registry {:a {:branch "task-a"}
                                  :b {:branch "task-b"}})
          result (dag-orch/merge-parent-branches! ctx task-def)]
      (is (= :anomalies/dag-multi-parent-strategy-unsupported
             (:anomaly/category result)))
      (is (= :sequential-merge (:merge/strategy result))
          "the requested strategy is echoed back in the anomaly so the
           operator/dashboard knows what was rejected")
      (is (contains? (:merge/supported result) :git-merge)
          "the anomaly carries the supported-strategies set so the user
           can see what's available right now"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.merge-parent-branches-integration-test)

  :leave-this-here)
