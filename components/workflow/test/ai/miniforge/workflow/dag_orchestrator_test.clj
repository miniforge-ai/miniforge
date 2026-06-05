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

(ns ai.miniforge.workflow.dag-orchestrator-test
  "Tests for DAG orchestrator: stratum wiring, conflict-aware batching,
   plan-to-DAG conversion with new decomposition fields, per-task base
   branch chaining, forest validation at plan time, artifact-of-record
   selection across multi-iteration repair cycles, and per-task event
   emission on both success and failure paths."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.dag-resilience :as resilience]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def id-a (random-uuid))
(def id-b (random-uuid))
(def id-c (random-uuid))
(def id-d (random-uuid))

;------------------------------------------------------------------------------ Layer 1
;; wire-stratum-deps tests

(deftest wire-stratum-deps-no-strata-test
  (testing "no-op when no tasks have :task/stratum"
    (let [tasks [{:task/id id-a :task/deps #{}}
                 {:task/id id-b :task/deps #{}}]]
      (is (= tasks (dag-orch/wire-stratum-deps tasks))))))

(deftest wire-stratum-deps-wires-across-strata-test
  (testing "stratum-1 tasks auto-depend on all stratum-0 tasks"
    (let [tasks [{:task/id id-a :task/deps #{} :task/stratum 0}
                 {:task/id id-b :task/deps #{} :task/stratum 0}
                 {:task/id id-c :task/deps #{} :task/stratum 1}]
          result (dag-orch/wire-stratum-deps tasks)
          c-deps (:task/deps (nth result 2))]
      (is (= #{id-a id-b} c-deps)))))

(deftest wire-stratum-deps-preserves-explicit-deps-test
  (testing "tasks with explicit deps are not overwritten"
    (let [tasks [{:task/id id-a :task/deps #{} :task/stratum 0}
                 {:task/id id-b :task/deps #{id-a} :task/stratum 1}]
          result (dag-orch/wire-stratum-deps tasks)
          b-deps (:task/deps (nth result 1))]
      (is (= #{id-a} b-deps)))))

(deftest wire-stratum-deps-three-strata-test
  (testing "stratum-2 depends on stratum-1, not stratum-0"
    (let [tasks [{:task/id id-a :task/deps #{} :task/stratum 0}
                 {:task/id id-b :task/deps #{} :task/stratum 1}
                 {:task/id id-c :task/deps #{} :task/stratum 2}]
          result (dag-orch/wire-stratum-deps tasks)]
      (is (= #{id-a} (:task/deps (nth result 1))))
      (is (= #{id-b} (:task/deps (nth result 2)))))))

;------------------------------------------------------------------------------ Layer 2
;; select-non-conflicting-batch tests

(deftest select-non-conflicting-batch-no-files-test
  (testing "selects all when no exclusive-files declared"
    (let [tasks [[id-a {:task/id id-a}]
                 [id-b {:task/id id-b}]
                 [id-c {:task/id id-c}]]
          batch (dag-orch/select-non-conflicting-batch tasks 4)]
      (is (= 3 (count batch))))))

(deftest select-non-conflicting-batch-respects-max-test
  (testing "respects max-parallel limit"
    (let [tasks [[id-a {:task/id id-a}]
                 [id-b {:task/id id-b}]
                 [id-c {:task/id id-c}]]
          batch (dag-orch/select-non-conflicting-batch tasks 2)]
      (is (= 2 (count batch))))))

(deftest select-non-conflicting-batch-skips-conflicts-test
  (testing "skips tasks with overlapping exclusive-files"
    (let [tasks [[id-a {:task/id id-a
                        :task/exclusive-files ["src/foo.clj" "src/bar.clj"]}]
                 [id-b {:task/id id-b
                        :task/exclusive-files ["src/bar.clj" "src/baz.clj"]}]
                 [id-c {:task/id id-c
                        :task/exclusive-files ["src/qux.clj"]}]]
          batch (dag-orch/select-non-conflicting-batch tasks 4)
          selected-ids (set (map first batch))]
      ;; a and c should be selected; b conflicts with a on src/bar.clj
      (is (contains? selected-ids id-a))
      (is (not (contains? selected-ids id-b)))
      (is (contains? selected-ids id-c)))))

(deftest select-non-conflicting-batch-mixed-declared-test
  (testing "tasks without exclusive-files don't conflict with anything"
    (let [tasks [[id-a {:task/id id-a
                        :task/exclusive-files ["src/foo.clj"]}]
                 [id-b {:task/id id-b}]
                 [id-c {:task/id id-c
                        :task/exclusive-files ["src/foo.clj"]}]]
          batch (dag-orch/select-non-conflicting-batch tasks 4)
          selected-ids (set (map first batch))]
      ;; a and b selected; c conflicts with a
      (is (contains? selected-ids id-a))
      (is (contains? selected-ids id-b))
      (is (not (contains? selected-ids id-c))))))

;------------------------------------------------------------------------------ Layer 3
;; plan->dag-tasks integration tests

(deftest plan-to-dag-tasks-forwards-new-fields-test
  (testing "component and exclusive-files are forwarded to DAG tasks"
    (let [plan {:plan/id (random-uuid)
                :plan/name "test"
                :plan/tasks [{:task/id id-a
                              :task/description "Agent work"
                              :task/type :implement
                              :task/component "agent"
                              :task/exclusive-files ["components/agent/src/foo.clj"]
                              :task/stratum 0}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          task (first dag-tasks)]
      (is (= "agent" (:task/component task)))
      (is (= ["components/agent/src/foo.clj"] (:task/exclusive-files task)))
      (is (= 0 (:task/stratum task))))))

(deftest plan-to-dag-tasks-stratum-wiring-integration-test
  (testing "stratum deps are auto-wired during plan->dag-tasks conversion"
    (let [plan {:plan/id (random-uuid)
                :plan/name "multi-stratum"
                :plan/tasks [{:task/id id-a
                              :task/description "Foundation"
                              :task/type :implement
                              :task/stratum 0}
                             {:task/id id-b
                              :task/description "Depends on foundation"
                              :task/type :implement
                              :task/stratum 1}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          task-b (second dag-tasks)]
      (is (contains? (:task/deps task-b) id-a)))))

(deftest plan-to-dag-tasks-backward-compat-test
  (testing "plan without new fields still converts correctly"
    (let [plan {:plan/id (random-uuid)
                :plan/name "old-style"
                :plan/tasks [{:task/id id-a
                              :task/description "Single task"
                              :task/type :implement}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          task (first dag-tasks)]
      (is (= id-a (:task/id task)))
      (is (nil? (:task/component task)))
      (is (nil? (:task/exclusive-files task))))))

;------------------------------------------------------------------------------ Layer 2
;; Per-task base branch chaining — `task-sub-opts` resolves the right
;; base branch from the per-workflow registry so a downstream task's
;; sub-workflow forks off its dependency's persisted branch instead of
;; the spec branch.

(defn- registry-context
  "Build a context with a populated branch registry. `entries` is a map of
   `task-id → {:branch ...}`. Returns the context map (not the atom) so
   tests reuse it for `task-sub-opts` calls."
  [entries default-branch]
  (let [reg (atom (reduce-kv dag/register-branch
                             (dag/create-branch-registry)
                             entries))]
    {:dag/branch-registry reg
     :execution/opts {:branch default-branch}}))

(deftest task-sub-opts-no-registry-resolves-to-default-test
  (testing "absent :dag/branch-registry on context is treated as an empty
            registry — `:branch` still resolves deterministically.

            Earlier draft kept a 'no-registry → omit :branch' fallback,
            but that fallback reproduced the pre-chaining bug (every
            sub-workflow forks off whatever main is now). There is no
            production caller that hits the no-registry path:
            `execute-dag-loop` always installs one. Failing back to a
            silent 'use whatever default' was a foot-gun, not a feature.

            The new contract: when task-def is supplied, `:branch` is
            ALWAYS set. With no registry and no `:execution/opts :branch`
            on context, the resolver falls back to 'main' — the same
            value `default-spec-branch` produces."
    (let [task-def {:task/id id-a :task/description "A" :task/deps #{}}
          opts (dag-orch/task-sub-opts {} task-def)]
      (is (= "main" (:branch opts))
          "no registry + no spec branch on context → default 'main'"))))

(deftest task-sub-opts-zero-deps-uses-default-test
  (testing "root task: with registry but no deps, base = default branch.
            The opts SHOULD carry :branch so the sub-workflow's
            acquire-environment is explicit about the fork point — even
            for roots we want to be deterministic, not 'whatever main is
            now'."
    (let [task-def {:task/id id-a :task/description "A" :task/deps #{}}
          ctx (registry-context {} "feat/spec")
          opts (dag-orch/task-sub-opts ctx task-def)]
      (is (= "feat/spec" (:branch opts))
          "zero-dep tasks fork from the spec branch resolved off context"))))

(deftest task-sub-opts-single-dep-uses-deps-branch-test
  (testing "single dep registered: base = the dep's persisted branch.
            This is the whole point of the chaining feature — the
            downstream sub-workflow sees its parent's work on disk."
    (let [task-def {:task/id id-b :task/description "B" :task/deps #{id-a}}
          ctx (registry-context {id-a {:branch "task-a"}} "main")
          opts (dag-orch/task-sub-opts ctx task-def)]
      (is (= "task-a" (:branch opts))
          "single-dep base resolves to the dep's branch, NOT the spec branch"))))

(deftest task-sub-opts-single-dep-unregistered-falls-back-test
  (testing "single dep not yet registered: fall back to default branch.
            Defensive: scheduler shouldn't allow this in practice (deps
            run first) but failing-soft beats blocking when the prior
            task crashed before persisting."
    (let [task-def {:task/id id-b :task/description "B" :task/deps #{id-a}}
          ctx (registry-context {} "main")
          opts (dag-orch/task-sub-opts ctx task-def)]
      (is (= "main" (:branch opts))
          "unregistered dep falls back to spec branch — no block"))))

(deftest task-sub-opts-multi-dep-attempts-merge-test
  (testing "Multi-parent task: v2 invokes merge-parent-branches! instead of
            rejecting at plan time. In a test context whose host repo
            doesn't have these branches, the merge attempt fails fast with
            a typed :anomalies/dag-multi-parent-branch-unresolvable
            anomaly, which task-sub-opts surfaces via :dag/merge-anomaly.
            The sub-workflow MUST NOT run with a stale :branch — opts
            should not silently fall through to a default branch on the
            multi-parent path. (Stage 2 will replace this anomaly path
            with the resolution sub-workflow.)"
    (let [task-def {:task/id id-c :task/description "C" :task/deps #{id-a id-b}}
          ctx (registry-context {id-a {:branch "task-a-not-in-test-repo"}
                                 id-b {:branch "task-b-not-in-test-repo"}}
                                "main")
          opts (dag-orch/task-sub-opts ctx task-def)]
      (is (not (contains? opts :branch))
          "merge anomaly must not produce a branch — sub-workflow would otherwise
           fork off a stale value")
      (is (some? (:dag/merge-anomaly opts))
          "the anomaly is surfaced for the caller (run-mini-workflow) to
           short-circuit on")
      (is (= :anomalies/dag-multi-parent-branch-unresolvable
             (get-in opts [:dag/merge-anomaly :anomaly/category]))
          "branch-unresolvable is the right category — branches don't exist in
           the test repo, so rev-parse fails before any merge is attempted"))))

(deftest task-sub-opts-multi-dep-rejects-invalid-branch-name-test
  (testing "Multi-parent task: a parent whose registered branch starts with '-'
            is rejected before git is invoked, surfacing a typed
            :anomalies/dag-multi-parent-branch-name-invalid anomaly."
    (let [task-def {:task/id id-c :task/description "C" :task/deps #{id-a id-b}}
          ctx (registry-context {id-a {:branch "-flag-injection"}
                                 id-b {:branch "task-b"}}
                                "main")
          opts (dag-orch/task-sub-opts ctx task-def)]
      (is (not (contains? opts :branch))
          "invalid branch name must not produce a usable :branch")
      (is (some? (:dag/merge-anomaly opts))
          "anomaly is surfaced for run-mini-workflow to short-circuit on")
      (is (= :anomalies/dag-multi-parent-branch-name-invalid
             (get-in opts [:dag/merge-anomaly :anomaly/category]))
          "leading-dash branch returns branch-name-invalid before any git invocation"))))

(deftest task-sub-opts-multi-dep-rejects-all-invalid-ref-shapes-test
  ;; Coverage extension after Copilot review on PR #1048: the
  ;; strengthened `valid-ref-name?` rejects more than just leading-dash
  ;; (revision operators, whitespace, ref-format-forbidden sequences,
  ;; empty string). Pin each rejection class so the predicate cannot
  ;; quietly weaken back to letting e.g. `"HEAD~1"` through to
  ;; `git rev-parse`.
  (testing "structurally invalid ref shapes each trigger branch-name-invalid"
    (doseq [[label bad-branch]
            [["empty string"            ""]
             ["leading dash (flag risk)" "-flag-injection"]
             ["whitespace"               "feat branch"]
             ["tilde (revision op)"      "HEAD~1"]
             ["caret (revision op)"      "HEAD^"]
             ["colon (refspec sep)"      "refs/heads:foo"]
             ["question mark glob"       "feat?"]
             ["asterisk glob"            "feat*"]
             ["open bracket"             "feat["]
             ["close bracket"            "feat]"]
             ["backslash"                "feat\\bad"]
             ["double dot range"         "main..feat"]
             ["at-brace reflog syntax"   "main@{1}"]
             ["double slash"             "feat//inner"]]]
      (let [task-def {:task/id id-c :task/description "C" :task/deps #{id-a id-b}}
            ctx (registry-context {id-a {:branch bad-branch}
                                   id-b {:branch "task-b"}}
                                  "main")
            opts (dag-orch/task-sub-opts ctx task-def)]
        (is (not (contains? opts :branch))
            (str label ": invalid branch must not produce a usable :branch"))
        (is (= :anomalies/dag-multi-parent-branch-name-invalid
               (get-in opts [:dag/merge-anomaly :anomaly/category]))
            (str label ": surfaces branch-name-invalid before git invocation"))))))

;------------------------------------------------------------------------------ Layer 3
;; v2 multi-parent: forest gate is dropped (informational logging only).
;; Diamond plans run end-to-end via merge-parent-branches!. With no
;; llm-backend in the test context, sub-workflows resolve to placeholder
;; results and don't actually invoke the merge path; the test below pins
;; that the gate is dropped and the run reaches completion. Real merge
;; behavior is exercised by the integration tests.

(deftest execute-plan-as-dag-accepts-forest-test
  (testing "linear chain is a forest — orchestrator runs to completion"
    (let [[logger _] (log/collecting-logger)
          plan {:plan/id (random-uuid)
                :plan/name "linear"
                :plan/tasks [{:task/id id-a :task/description "A"
                              :task/type :implement :task/dependencies []}
                             {:task/id id-b :task/description "B"
                              :task/type :implement :task/dependencies [id-a]}]}
          result (dag-orch/execute-plan-as-dag plan {:logger logger})]
      (is (:success? result) "forest plan should run to success")
      (is (= 2 (:tasks-completed result))))))

(deftest execute-plan-as-dag-accepts-diamond-test-v2
  (testing "v2: diamond (multi-parent) plan is no longer rejected at plan
            time. The forest gate is dropped; multi-parent tasks run
            through `merge-parent-branches!`. With placeholder execution
            (no llm-backend) all four tasks complete; the merge code path
            isn't exercised here because placeholder-result short-circuits
            before sub-workflow invocation. Real merge behavior is in
            the integration tests; this test is the unit-level pin that
            v1's plan-time rejection is gone."
    (let [[logger entries] (log/collecting-logger)
          plan {:plan/id (random-uuid)
                :plan/name "diamond"
                :plan/tasks [{:task/id id-a :task/description "A"
                              :task/type :implement :task/dependencies []}
                             {:task/id id-b :task/description "B"
                              :task/type :implement :task/dependencies [id-a]}
                             {:task/id id-c :task/description "C"
                              :task/type :implement :task/dependencies [id-a]}
                             {:task/id id-d :task/description "D"
                              :task/type :implement :task/dependencies [id-b id-c]}]}
          result (dag-orch/execute-plan-as-dag plan {:logger logger})]
      (is (:success? result)
          "v2 runs diamond plans end-to-end")
      (is (= 4 (:tasks-completed result))
          "all four tasks complete (placeholder execution, no real merge)")
      (is (some #(= :dag/multi-parent-detected (:log/event %)) @entries)
          "the multi-parent detection is logged for plan-quality observability —
           dashboard surfaces fan-in even though it doesn't reject"))))

;------------------------------------------------------------------------------ aggregate-results — metrics rollup across ok + err

;; Cost-USD assertions tolerate IEEE-754 rounding because :cost-usd is
;; a double in this layer. 0.01 + 0.03 in IEEE doubles ≈
;; 0.039999999999999994 — direct = comparison flakes per host. A
;; sub-cent epsilon is well below any rollup precision a caller
;; relies on.
(def ^:private cost-usd-epsilon 1.0e-6)

(defn- approx=
  [expected actual]
  (< (Math/abs (double (- expected actual))) cost-usd-epsilon))

;; Test fixtures lifted into named data so the assertion shape and
;; the input shape can be read together. Lets each row's metrics
;; carry semantic meaning beyond raw integers in deftest body.
(def ^:private aggregate-rollup-fixture
  {:ok    {:tokens 1000 :cost-usd 0.01 :duration-ms 5000}
   :err   {:tokens 2500 :cost-usd 0.03 :duration-ms 12000}
   :sum   {:tokens 3500 :cost-usd 0.04 :duration-ms 17000}})

(deftest aggregate-results-sums-metrics-from-ok-and-err-test
  (testing "Both dag/ok and dag/err results contribute to the
            aggregate :total-tokens / :total-cost / :total-duration.
            Pre-fix the rollup only inspected `[:data :metrics ...]`
            which silently dropped every failed task — for a dogfood
            run with all tasks failing, total-tokens reported zero
            despite a per-task implementer carrying real token counts.
            With the fix, dag/err's `[:error :data :metrics ...]`
            shape is also picked up so failed tasks contribute to the
            rollup the same way completed ones do."
    (let [{:keys [ok err sum]} aggregate-rollup-fixture
          ok-result  (dag/ok  {:metrics ok})
          err-result (dag/err :task-execution-failed "boom"
                              {:metrics err})
          mixed      {:t-ok ok-result :t-err err-result}
          agg        (dag-orch/aggregate-results mixed)]
      (is (= (:tokens sum) (:total-tokens agg))
          "ok + err token contributions both flow into the rollup")
      (is (approx= (:cost-usd sum) (:total-cost agg))
          ":cost-usd from both results sums correctly via the ok/err-
           aware accessor (within IEEE-double rounding)")
      (is (= (:duration-ms sum) (:total-duration agg))
          ":duration-ms also rolls up from both shapes"))))

(deftest aggregate-results-handles-missing-metrics-gracefully-test
  (testing "Tasks with no :metrics key on either ok or err shape
            contribute zero rather than throwing or producing nil
            sums. Older callers that build dag/ok without :metrics
            (placeholder paths) must keep working."
    (let [no-metrics-ok  (dag/ok  {:status :implemented :artifacts []})
          no-metrics-err (dag/err :task-execution-failed "boom" {})
          mixed          {:t1 no-metrics-ok :t2 no-metrics-err}
          agg            (dag-orch/aggregate-results mixed)]
      (is (= 0 (:total-tokens agg)))
      (is (= 0.0 (:total-cost agg)))
      (is (= 0 (:total-duration agg))))))

;------------------------------------------------------------------------------ Layer 4
;; Artifact-of-record selection across repair iterations.
;;
;; `:execution/artifacts` accumulates across every phase invocation
;; (state.clj:186 `update :execution/artifacts into ...`). When a task
;; succeeds after one or more review:changes-requested cycles, the
;; vector ends up holding the failing-iteration artifacts AHEAD of
;; the succeeding ones'. `run-mini-workflow` must pick the LAST
;; entry so the `:dag/task-completed` event reports the artifact
;; that actually shipped — not an early failing iteration that was
;; later repaired. Bug observed 2026-05-12 on PR #861.

(deftest run-mini-workflow-selects-last-artifact-test
  (testing "When :execution/artifacts has multiple entries from repair
            iterations, the wf-result :artifact must be the LAST entry
            (the final iteration's), not the first (an earlier failing
            iteration's). See project_dogfood_findings_2026_05_12.md
            → 'Stale verifier feedback'."
    (let [early-artifact  {:status :success
                           :summary "iter 1 — runner_events.clj modified but core change is absent"}
          middle-artifact {:status :success
                           :summary "iter 2 — still broken"}
          final-artifact  {:status :success
                           :environment-id "task-7bedbf77"
                           :summary "iter 3 — :meta diagnostic block added, tests pass"}
          stub-result     {:execution/status :completed
                           :execution/artifacts [early-artifact middle-artifact final-artifact]
                           :execution/metrics {:tokens 100 :cost-usd 0.05 :duration-ms 1000}
                           :execution/phase-results {}}
          context         {:execution/run-pipeline-fn (fn [_sw _in _opts] stub-result)
                           :execution/workflow {:workflow/pipeline []}
                           :execution/opts {:branch "main"}}
          task-def        {:task/id id-a
                           :task/description "Test multi-iter task"
                           :task/deps #{}}
          wf-result       (dag-orch/run-mini-workflow task-def context)]
      (is (:success? wf-result)
          "phase/succeeded? recognizes :execution/status :completed")
      (is (= final-artifact (:artifact wf-result))
          "artifact-of-record is the LAST entry, not the first — picking
           `(first artifacts)` would surface the iter-1 'core change is absent'
           summary even though iter 3 shipped the fix"))))

(deftest run-mini-workflow-single-artifact-still-selected-test
  (testing "Single-iteration happy path: with exactly one artifact in the
            vector, `(last artifacts)` selects it just as `(first artifacts)`
            would. Confirms the fix doesn't regress the no-repair case."
    (let [only-artifact {:status :success
                         :summary "shipped first time"}
          stub-result   {:execution/status :completed
                         :execution/artifacts [only-artifact]
                         :execution/metrics {:tokens 50 :cost-usd 0.02 :duration-ms 500}
                         :execution/phase-results {}}
          context       {:execution/run-pipeline-fn (fn [_sw _in _opts] stub-result)
                         :execution/workflow {:workflow/pipeline []}
                         :execution/opts {:branch "main"}}
          task-def      {:task/id id-a
                         :task/description "Happy path task"
                         :task/deps #{}}
          wf-result     (dag-orch/run-mini-workflow task-def context)]
      (is (:success? wf-result))
      (is (= only-artifact (:artifact wf-result))
          "single-artifact case still works"))))

;------------------------------------------------------------------------------ Layer 5
;; emit-batch-events! per-task event emission
;;
;; Stub at the resilience emit fns rather than the event-stream
;; interface — the orchestrator's contract is "call the right emit
;; fn with the right args"; what those fns do with publish! is the
;; resilience namespace's concern.

;; Number of tasks in the all-success and all-failure emit-batch test fixtures.
;; Both tests build a results map of {id-a ..., id-b ...} — two tasks — so
;; "all tasks completed" and "all tasks failed" both expect this count.
(def ^:private emit-batch-task-count 2)

;; Sentinel for the complementary side of a one-sided batch (i.e. when all
;; tasks succeed the failure count is zero, and vice-versa).
(def ^:private emit-batch-no-calls 0)

(deftest emit-batch-events-emits-task-completed-for-success-test
  (testing ":dag/task-completed fires for every dag/ok? result in batch"
    (let [completed-calls (atom [])
          failed-calls    (atom [])]
      (with-redefs [resilience/emit-dag-task-completed!
                    (fn [_es _wf tid result] (swap! completed-calls conj [tid result]))
                    resilience/emit-dag-task-failed!
                    (fn [_es _wf tid result] (swap! failed-calls conj [tid result]))]
        (let [results {id-a {:ok? true :data {:summary "done a"}}
                       id-b {:ok? true :data {:summary "done b"}}}]
          (dag-orch/emit-batch-events! results ::stream "wf-1")
          (is (= emit-batch-task-count (count @completed-calls)))
          (is (= emit-batch-no-calls (count @failed-calls)))
          (is (= #{id-a id-b} (set (map first @completed-calls)))))))))

(deftest emit-batch-events-emits-task-failed-for-failures-test
  (testing ":dag/task-failed fires for every dag/err? result in batch"
    (let [completed-calls (atom [])
          failed-calls    (atom [])]
      (with-redefs [resilience/emit-dag-task-completed!
                    (fn [_es _wf tid result] (swap! completed-calls conj [tid result]))
                    resilience/emit-dag-task-failed!
                    (fn [_es _wf tid result] (swap! failed-calls conj [tid result]))]
        (let [results {id-a {:ok? false :error {:code :ci-failed :message "boom a"}}
                       id-b {:ok? false :error {:code :ci-failed :message "boom b"}}}]
          (dag-orch/emit-batch-events! results ::stream "wf-1")
          (is (= emit-batch-no-calls (count @completed-calls)))
          (is (= emit-batch-task-count (count @failed-calls)))
          (is (= #{id-a id-b} (set (map first @failed-calls))))
          (is (= #{"boom a" "boom b"}
                 (set (map #(get-in (second %) [:error :message]) @failed-calls)))))))))

(deftest emit-batch-events-emits-both-events-for-mixed-batch-test
  (testing "mixed batch produces completed for ok and failed for err.
            Asserts set membership not vector order — results is a hash-map
            and iteration order is not guaranteed across JVM / Clojure
            implementations."
    (let [completed-calls (atom [])
          failed-calls    (atom [])]
      (with-redefs [resilience/emit-dag-task-completed!
                    (fn [_es _wf tid _result] (swap! completed-calls conj tid))
                    resilience/emit-dag-task-failed!
                    (fn [_es _wf tid _result] (swap! failed-calls conj tid))]
        (let [results {id-a {:ok? true :data {:summary "ok"}}
                       id-b {:ok? false :error {:code :timeout :message "fail"}}}]
          (dag-orch/emit-batch-events! results ::stream "wf-1")
          (is (= #{id-a} (set @completed-calls)))
          (is (= #{id-b} (set @failed-calls))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.dag-orchestrator-test)

  :leave-this-here)
