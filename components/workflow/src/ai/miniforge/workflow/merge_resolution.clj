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

(ns ai.miniforge.workflow.merge-resolution
  "Automated resolution of multi-parent merge conflicts (v2 Stage 2B,
   spec §6.1).

   When `merge-parent-branches!` produces a conflict anomaly, the
   orchestrator hands the conflicted worktree to `resolve-conflict!`
   here, which iterates an agent → curator → verify loop until either
   the conflicts are resolved cleanly or a terminal condition fires
   (budget exhausted, agent stuck, verify never passes).

   Stage 2B scope: the loop scaffolding + the curator/verify wiring +
   the namespaced-ref commit on success. The agent step is
   parameterized via an `agent-edit-fn` so tests can inject mock
   resolutions and the production wiring can default to a no-op stub
   until Stage 2C lands the real LLM-driven resolution agent. With the
   default stub, conflicts still terminate as
   `:dag-multi-parent-unresolvable` (with `:resolution/reason
   :curator/recurring-conflict` after two no-progress iterations) —
   exactly as in Stage 1B, just routed through the loop.

   Spec §6.1.2 curator and §10.6 budget shape live in this layer too:
   the loop calls the `:merge-resolution` curator method between
   iterations to detect markers-not-resolved and recurring-conflict;
   the budget defaults to `{:max-iterations 5 :stagnation-cap 2}` per
   the round-2 user direction (small iteration cap so agents that
   aren't progressing don't burn the rest of the budget; stagnation
   cap caught by the curator's recurring-conflict path)."
  (:require
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.messages :as messages]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

;; Constants -----------------------------------------------------------

(def ^:private default-budget
  "Per spec §10.6: small iteration cap (resolution is a focused task;
   if the agent can't converge in a few rounds the conflict probably
   needs human eyes) plus a stagnation cap (the curator detects
   recurring-conflict and terminates without burning the full budget).
   The stagnation-cap is the actionable lever — it ends loops that
   aren't making progress, where waste actually comes from."
  {:max-iterations 5
   :stagnation-cap 2})

;; Stub agent + verify --------------------------------------------------

(defn no-op-agent-edit-fn
  "Stage 2B's default `agent-edit-fn`: does nothing. With this stub the
   curator finds the same conflict markers each iteration, recurring-
   conflict fires, and the loop terminates as if the agent gave up.
   Stage 2C replaces this with a real LLM-driven implementer that reads
   the conflict info and edits the worktree."
  [_worktree-path _conflict-info _iteration]
  ;; Returns response/success with no edits — the curator's marker
  ;; scan is what actually drives the loop's progress detection.
  (response/success {:edits/applied 0
                     :edits/files []}
                    nil))

(defn always-pass-verify-fn
  "Stage 2B's default `verify-fn`: assumes verify passes once markers
   are gone. Stage 4 will wire this to a real `bb test` invocation per
   spec §6.1.1. Until then, the curator's marker check IS the
   resolution gate; verify is plumbed but trivial."
  [_worktree-path]
  {:ok? true})

;; Anomaly + result factories ------------------------------------------

(defn- unresolvable-anomaly
  "Spec §6.1 terminal: the resolution sub-workflow couldn't produce a
   clean merge within budget / before the curator declared the agent
   stuck. Carries enough context for an operator dashboard to surface
   what was tried and what state the worktree was last in."
  [{:keys [task-id strategy parents conflicts input-key
           reason iterations last-attempt-ref]}]
  {:anomaly/category :anomalies/dag-multi-parent-unresolvable
   :anomaly/message  (messages/t :dag.merge.resolution/unresolvable)
   :task/id          task-id
   :merge/parents    parents
   :merge/conflicts  conflicts
   :merge/strategy   strategy
   :merge/input-key  input-key
   :resolution/reason reason
   :resolution/iterations iterations
   :resolution/last-attempt-ref last-attempt-ref})

(defn- resolution-success
  "Successful resolution result. The orchestrator wraps this in
   `merge-ok-result` after writing the namespaced ref."
  [commit-sha iterations]
  (dag/ok {:commit-sha commit-sha
           :iterations iterations
           :resolved? true}))

;; Git helpers ---------------------------------------------------------
;; Defensive against shell exceptions so the loop branches on `:exit`
;; rather than wrapping in try/catch at every call site.

(defn- run-git
  [cwd & args]
  (try (apply shell/sh "git" "-C" cwd args)
       (catch Exception e {:exit -1 :out "" :err (.getMessage e)})))

(defn- commit-resolution!
  "Stage the agent's edits and commit them in `worktree-path`. Returns
   `{:ok? true :commit-sha <sha>}` or `{:ok? false :anomaly ...}`.
   Uses the same pinned-flags convention as the upstream merge
   (no-edit / no-gpg-sign / no-verify) so the resolution commit's
   shape is consistent with the merge it's resolving."
  [worktree-path task-id parents iterations]
  (let [add (run-git worktree-path "add" "-A")]
    (if-not (zero? (:exit add))
      {:ok? false :git-result add}
      (let [header (messages/t :dag.merge.resolution/commit-message-header
                               {:task-id task-id})
            body   (messages/t :dag.merge.resolution/commit-message-body
                               {:parent-count (count parents)
                                :iterations   iterations
                                :s            (if (= 1 iterations) "" "s")})
            message (str header "\n\n" body)
            commit (run-git worktree-path "commit"
                            "--no-edit" "--no-gpg-sign" "--no-verify"
                            "-m" message)]
        (if-not (zero? (:exit commit))
          {:ok? false :git-result commit}
          (let [head (run-git worktree-path "rev-parse" "HEAD")]
            (if (zero? (:exit head))
              {:ok? true :commit-sha (str/trim (:out head))}
              {:ok? false :git-result head})))))))

;; Loop helpers --------------------------------------------------------

(defn- run-curator-check
  "Spec §6.1.2: invoke the merge-resolution curator on the current
   worktree state, threading the prior iteration's conflicted-paths
   so recurring-conflict can fire."
  [worktree-path prior-paths]
  (agent/curate {:curator/kind :merge-resolution
                 :worktree-path worktree-path
                 :prior-conflicted-paths prior-paths}))

(defn- curator-error-code
  "Pull the structured `:code` keyword from a curator error response.
   Returns nil for a success response."
  [curator-result]
  (when (response/error? curator-result)
    (get-in curator-result [:error :data :code])))

(defn- curator-conflicted-paths
  "Pull the conflicted-paths vector from a curator error response —
   used as the next iteration's `:prior-conflicted-paths` input so
   recurrence detection can fire."
  [curator-result]
  (get-in curator-result [:error :data :conflicted-paths]))

(defn- terminal-result
  "Build the terminal anomaly when the loop can't make progress.
   Centralizes message + iteration accounting."
  [{:keys [conflict-input reason iterations last-attempt-ref]}]
  (unresolvable-anomaly
   (-> conflict-input
       (assoc :reason reason
              :iterations iterations
              :last-attempt-ref last-attempt-ref))))

;; Public API ----------------------------------------------------------

(defn resolve-conflict!
  "Run the v2 conflict-resolution iteration loop per spec §6.1.

   Inputs (a single map for clarity at the call site):
   - `:conflict-input`  — the conflict anomaly produced by
     `merge-parent-branches!` (parents, conflicts, strategy, input-key).
   - `:host-repo`       — host repo path; the resolution commit's SHA
     is reachable from the host repo via the merge worktree's shared
     object store.
   - `:worktree-path`   — the temp worktree where the merge attempt
     happened; the agent edits run here.
   - `:task-id`         — for the resolution commit message + anomaly
     payload.
   - `:budget`          — `{:max-iterations N :stagnation-cap M}`;
     defaults to `default-budget`.
   - `:agent-edit-fn`   — `(fn [worktree-path conflict-input iteration])`;
     mutates the worktree to (try to) resolve markers. Defaults to
     `no-op-agent-edit-fn` (no real LLM agent yet — Stage 2C wires it
     in). Test code injects mocks here.
   - `:verify-fn`       — `(fn [worktree-path])` returns
     `{:ok? bool}`. Defaults to `always-pass-verify-fn` (markers-cleared
     IS the gate until Stage 4 wires real `bb test`).

   Returns either:
   - `dag/ok {:commit-sha <sha> :iterations <n> :resolved? true}` on
     successful resolution. The caller writes the SHA to the namespaced
     ref via `update-ref` and returns the standard merge-success shape.
   - The `:anomalies/dag-multi-parent-unresolvable` anomaly when the
     loop terminates without resolving (budget exhausted, recurring-
     conflict, markers-not-resolved beyond cap, verify never passes,
     resolution-commit fails)."
  [{:keys [conflict-input host-repo worktree-path task-id budget
           agent-edit-fn verify-fn]
    :or   {budget         default-budget
           agent-edit-fn  no-op-agent-edit-fn
           verify-fn      always-pass-verify-fn}}]
  (let [parents (:merge/parents conflict-input)]
    (loop [iteration 0
           prior-paths nil]
      (cond
        ;; Budget exhausted before resolution.
        (>= iteration (:max-iterations budget))
        (terminal-result {:conflict-input conflict-input
                          :reason :budget-exhausted
                          :iterations iteration
                          :last-attempt-ref worktree-path})

        :else
        (do
          ;; The agent edits the worktree (or doesn't, if it's the stub).
          (agent-edit-fn worktree-path conflict-input iteration)
          (let [curator (run-curator-check worktree-path prior-paths)
                code    (curator-error-code curator)]
            (cond
              ;; Curator declared the agent stuck.
              (= code :curator/recurring-conflict)
              (terminal-result {:conflict-input conflict-input
                                :reason :curator/recurring-conflict
                                :iterations (inc iteration)
                                :last-attempt-ref worktree-path})

              ;; Markers gone — run verify.
              (response/success? curator)
              (let [verify (verify-fn worktree-path)]
                (if (:ok? verify)
                  (let [commit (commit-resolution! worktree-path task-id
                                                   parents (inc iteration))]
                    (if (:ok? commit)
                      (resolution-success (:commit-sha commit) (inc iteration))
                      (terminal-result {:conflict-input conflict-input
                                        :reason :resolution-commit-failed
                                        :iterations (inc iteration)
                                        :last-attempt-ref worktree-path})))
                  ;; Verify failed: the agent's edits broke tests.
                  ;; Loop again; same paths since markers are gone — but
                  ;; rather than risk infinite-loop on always-failing
                  ;; verify, we treat this as a no-progress iteration
                  ;; and let budget-exhausted catch it.
                  (recur (inc iteration) prior-paths)))

              ;; Curator says markers-not-resolved (or worktree-missing).
              ;; Loop with the new path set so recurrence can fire next
              ;; iteration.
              :else
              (recur (inc iteration)
                     (set (curator-conflicted-paths curator))))))))))

;; Rich Comment --------------------------------------------------------
(comment
  ;; A loop with the no-op stub on a worktree that has markers will
  ;; recurring-conflict on iteration 1 and terminate on iteration 2:
  (resolve-conflict!
   {:conflict-input {:task/id "task-c"
                     :merge/parents [{:task/id :a :commit-sha "aaa"}
                                     {:task/id :b :commit-sha "bbb"}]
                     :merge/strategy :git-merge}
    :host-repo "/tmp/some-host-repo"
    :worktree-path "/tmp/some-worktree-with-markers"
    :task-id "task-c"})

  :leave-this-here)
