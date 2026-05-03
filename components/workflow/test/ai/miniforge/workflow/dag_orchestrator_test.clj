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
   branch chaining, and forest validation at plan time."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]))

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

(deftest task-sub-opts-multi-dep-omits-branch-test
  (testing "multi-parent task: registry returns an anomaly map; opts must
            NOT carry :branch (we'd be passing a map where a string is
            expected). v1 is supposed to reject these at plan time via
            execute-plan-as-dag, so reaching this branch in production
            is a bug — but the local fallback keeps the orchestrator
            from crashing if validation is ever bypassed."
    (let [task-def {:task/id id-c :task/description "C" :task/deps #{id-a id-b}}
          ctx (registry-context {id-a {:branch "task-a"}
                                 id-b {:branch "task-b"}}
                                "main")
          opts (dag-orch/task-sub-opts ctx task-def)]
      (is (not (contains? opts :branch))
          "anomaly result must not be passed through as a branch name"))))

;------------------------------------------------------------------------------ Layer 3
;; Forest validation at plan time — multi-parent DAGs are rejected
;; before any task starts so non-forest plans fail loud and early
;; instead of after some tasks have already burned tokens.

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

(deftest execute-plan-as-dag-rejects-diamond-test
  (testing "diamond (multi-parent) plan rejected before any task runs.
            The orchestrator surfaces the anomaly at plan-validation
            time rather than at task-execution time so callers can
            linearize or split — and so we don't burn tokens on tasks
            that will end up in a doomed merge."
    (let [[logger _] (log/collecting-logger)
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
      (is (false? (:success? result))
          "non-forest plans must short-circuit the orchestrator")
      (is (= 0 (:tasks-completed result))
          "no tasks should run when the plan is rejected at validation time")
      (is (= :anomalies/dag-non-forest
             (get-in result [:error :anomaly/category]))
          "error carries the canonical anomaly category for downstream surfaces")
      (is (some #(= id-d (:task/id %))
                (get-in result [:error :multi-parent-tasks]))
          "the violation list pinpoints the offending task id"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.dag-orchestrator-test)

  :leave-this-here)
