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
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

;; Constants -----------------------------------------------------------
;; Configuration is data, not code. Tunables live under
;; resources/config/workflow/dag/merge-resolution-defaults.edn so they
;; can be audited and overridden without touching the namespace.

(def ^:private resolution-defaults-path
  "config/workflow/dag/merge-resolution-defaults.edn")

(def ^:private resolution-defaults
  "Loaded once at namespace load. Throws (loud) if the resource is
   missing — that would be a packaging bug, not a runtime condition."
  (delay
    (-> resolution-defaults-path
        io/resource
        slurp
        edn/read-string)))

(defn- default-budget
  "Spec §10.6 budget shape: `{:max-iterations N :stagnation-cap M}`.
   Loaded from the EDN config; see the comments there for the
   calibration rationale."
  []
  (:default-budget @resolution-defaults))

(defn- min-stagnation-cap
  "Floor on the consecutive-recurrence count that terminates the
   loop. Misconfigured zero or negative would never terminate; the
   floor prevents that."
  []
  (:min-stagnation-cap @resolution-defaults))

(defn- no-recurrence-count
  "The 'no recurrence yet' value for the consecutive-recurrence
   counter. Named so loop arity reads as intent rather than a stray
   `0`."
  []
  (:no-recurrence-count @resolution-defaults))

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
   resolution gate; verify is plumbed but trivial. Returns the standard
   response/success shape so the loop can use response/success? to
   branch — no bespoke `{:ok? true}` map invented locally."
  [_worktree-path]
  (response/success {:verify/skipped? true} nil))

;; Resolution prompt builders (Stage 2C, spec §6.1.3) ------------------
;; Pure-data assembly of the task input that the implementer agent
;; will receive when Stage 2C's `agent-driven-edit-fn` lands in a
;; follow-up slice. Splitting the prompt-building helpers from the
;; agent-invocation closure keeps each slice independently reviewable
;; and lets a policy pack swap catalog templates without touching the
;; invocation wiring.
;;
;; Spec §6.1.3 — policy-overridable prompts. The default prompt
;; templates live in the workflow message catalog under
;; :dag.merge.resolution.prompt/*. A loaded policy pack can substitute
;; its own templates through the same plumbing the policy interface
;; already uses for other agent prompts — that override happens at the
;; messages/t lookup layer, not here.

(defn- format-parent-line
  [parent]
  (messages/t :dag.merge.resolution.prompt/parent-line
              {:task-id    (:task/id parent)
               :commit-sha (:commit-sha parent)}))

(defn- format-conflict-line
  [conflict]
  (messages/t :dag.merge.resolution.prompt/conflict-line
              {:path   (:path conflict)
               :stages (str/join "/" (:stages conflict))}))

(defn- build-resolution-prompt
  "Build the resolution-task description from the conflict-input.
   Iteration count and max-iterations let the prompt give the agent
   awareness of its budget. Returns a string suitable for
   `:task/description` on the implementer's task input."
  [conflict-input iteration max-iterations]
  (let [parents (:merge/parents conflict-input)
        conflicts (:merge/conflicts conflict-input)
        strategy (:merge/strategy conflict-input)]
    (str/join "\n\n"
              [(messages/t :dag.merge.resolution.prompt/header
                           {:parent-count (count parents)})
               (messages/t :dag.merge.resolution.prompt/iteration
                           {:iteration (inc iteration)
                            :max-iterations max-iterations})
               (messages/t :dag.merge.resolution.prompt/strategy-line
                           {:strategy strategy})
               (str (messages/t :dag.merge.resolution.prompt/parents-header)
                    "\n"
                    (str/join "\n" (map format-parent-line parents)))
               (str (messages/t :dag.merge.resolution.prompt/conflicts-header)
                    "\n"
                    (str/join "\n" (map format-conflict-line conflicts)))
               (messages/t :dag.merge.resolution.prompt/instructions)])))

(defn- read-conflict-file
  "Slurp one conflicted file from `worktree-path`. Returns the
   `{:path :content :truncated?}` map the implementer's
   `format-existing-files` expects, or nil if the file is missing or
   unreadable (skip rather than fail — the agent can still try to
   resolve markers in the files we did manage to read; the curator's
   marker scan on the next iteration is the authoritative gate)."
  [worktree-path conflict]
  (let [rel-path (:path conflict)
        f (io/file worktree-path rel-path)]
    (when (and (.exists f) (.canRead f))
      (try {:path rel-path
            :content (slurp f)
            :truncated? false}
           (catch Exception _ nil)))))

(defn- build-resolution-task
  "Build the task map agent.implementer expects.

   `:task/existing-files` is a vector of `{:path :content :truncated?}`
   maps — the shape the implementer's `format-existing-files` and
   context-cache writer expect. Path-strings would yield nil paths and
   nil contents in the prompt and poison the session context-cache.
   Files that can't be read are skipped (logged would be better but
   the resolution loop owns logging; here we just emit what the agent
   can see)."
  [conflict-input worktree-path iteration max-iterations]
  {:task/description       (build-resolution-prompt conflict-input
                                                    iteration max-iterations)
   :task/type              :merge-resolution
   :task/existing-files    (->> (:merge/conflicts conflict-input)
                                (keep (partial read-conflict-file
                                                worktree-path))
                                vec)
   :task/worktree-path     worktree-path})

;; Resolution agent invocation (Stage 2C) ------------------------------
;; The agent-driven edit-fn invokes the existing implementer agent
;; (agent/create-implementer + agent/invoke) on the synthetic task
;; built above. The implementer's tool-call plumbing (Edit/Write) does
;; the actual file edits in the worktree; the agent-edit-fn just
;; builds the task input and reports the invocation result up to the
;; resolution loop, which checks markers cleared on the next pass.

(defn agent-driven-edit-fn
  "Construct an `agent-edit-fn` that delegates to the existing
   implementer agent for actual conflict resolution. Spec §6.1.

   `agent-context` is a map carrying at least `:llm-backend` (resolved
   LLM client) and optionally `:logger`. The closure captures a single
   implementer agent at construction so multiple iterations share its
   config; the agent's `agent/invoke` is called per iteration with
   the per-iteration task built from the conflict info.

   Returns the agent's response on success (the implementer wrote
   files via Edit/Write tool calls; the curator's marker scan picks
   up the worktree state on the next iteration). Returns
   response/error on agent invocation failure — the loop counts that
   as a no-progress iteration and lets the budget catch it.

   The max-iterations argument feeds the prompt so the agent knows
   its budget; falls back to the default-budget if absent."
  [{:keys [llm-backend logger max-iterations]}]
  (let [max-iters (or max-iterations (:max-iterations (default-budget)))
        impl     (agent/create-implementer (cond-> {}
                                             logger (assoc :logger logger)))]
    (fn agent-edit-step [worktree-path conflict-input iteration]
      (let [task (build-resolution-task conflict-input worktree-path
                                        iteration max-iters)
            invoke-ctx (cond-> {:execution/worktree-path worktree-path}
                         llm-backend (assoc :llm-backend llm-backend))]
        (try (agent/invoke impl task invoke-ctx)
             (catch Exception e
               (response/error
                (messages/t :dag.merge.resolution.prompt/agent-error
                            {:error (str e)})
                {:data {:exception/class (.getName (class e))
                        :iteration iteration}})))))))

;; Anomaly + result factories ------------------------------------------

(defn- unresolvable-anomaly
  "Spec §6.1 terminal: the resolution sub-workflow couldn't produce a
   clean merge within budget / before the curator declared the agent
   stuck. Carries enough context for an operator dashboard to surface
   what was tried and what state the worktree was last in.

   Inputs from the upstream conflict-anomaly are read with their
   namespaced keys (`:task/id`, `:merge/strategy`, `:merge/parents`,
   `:merge/conflicts`, `:merge/input-key`); resolution-loop bookkeeping
   uses unnamespaced keys (`:reason`, `:iterations`, `:last-attempt-ref`)
   added by `terminal-result`."
  [{task-id   :task/id
    strategy  :merge/strategy
    parents   :merge/parents
    conflicts :merge/conflicts
    input-key :merge/input-key
    :keys     [reason iterations last-attempt-ref]}]
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
   `(response/success {:commit-sha <sha>})` on success, or
   `(response/error <message> {:data {:git-result ...}})` on failure.
   Uses the same pinned-flags convention as the upstream merge
   (no-edit / no-gpg-sign / no-verify) so the resolution commit's
   shape is consistent with the merge it's resolving."
  [worktree-path task-id parents iterations]
  (let [commit-failed (fn commit-failed [git-result]
                        (response/error
                         (messages/t :dag.merge.resolution/commit-failed)
                         {:data {:git-result git-result}}))
        add (run-git worktree-path "add" "-A")]
    (if-not (zero? (:exit add))
      (commit-failed add)
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
          (commit-failed commit)
          (let [head (run-git worktree-path "rev-parse" "HEAD")]
            (if (zero? (:exit head))
              (response/success {:commit-sha (str/trim (:out head))} nil)
              (commit-failed head))))))))

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
    :or   {agent-edit-fn  no-op-agent-edit-fn
           verify-fn      always-pass-verify-fn}}]
  (let [budget (or budget (default-budget))
        parents (:merge/parents conflict-input)
        max-iterations (:max-iterations budget)
        stagnation-floor (min-stagnation-cap)
        no-recurrence (no-recurrence-count)
        ;; Spec §10.6: stagnation-cap is the consecutive-recurrence
        ;; count that terminates the loop. Floored at min-stagnation-cap
        ;; so a misconfigured zero or negative can't loop forever.
        stagnation-cap (max stagnation-floor
                            (or (:stagnation-cap budget) stagnation-floor))]
    (loop [iteration 0
           prior-paths nil
           consecutive-recurrence no-recurrence]
      (cond
        ;; Budget exhausted before resolution.
        (>= iteration max-iterations)
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
              ;; Curator: agent is stuck on the same path set. Track
              ;; consecutive recurrences and only terminate when we hit
              ;; the stagnation cap — gives the agent a chance to break
              ;; out (relevant once Stage 2C wires the real LLM).
              (= code :curator/recurring-conflict)
              (let [stagnated (inc consecutive-recurrence)]
                (if (>= stagnated stagnation-cap)
                  (terminal-result {:conflict-input conflict-input
                                    :reason :curator/recurring-conflict
                                    :iterations (inc iteration)
                                    :last-attempt-ref worktree-path})
                  ;; Below the cap — loop with same prior-paths so a
                  ;; subsequent identical iteration counts as another
                  ;; consecutive recurrence.
                  (recur (inc iteration) prior-paths stagnated)))

              ;; Curator: worktree is missing or some other infrastructure
              ;; fault. Surface immediately rather than spinning to
              ;; budget exhaustion — the operator needs the original
              ;; error category, not a generic unresolvable.
              (= code :curator/worktree-missing)
              (terminal-result {:conflict-input conflict-input
                                :reason :curator/worktree-missing
                                :iterations (inc iteration)
                                :last-attempt-ref worktree-path})

              ;; Markers gone — run verify.
              (response/success? curator)
              (let [verify-result (verify-fn worktree-path)]
                (if (response/success? verify-result)
                  (let [commit (commit-resolution! worktree-path task-id
                                                   parents (inc iteration))]
                    (if (response/success? commit)
                      (resolution-success (get-in commit [:output :commit-sha])
                                          (inc iteration))
                      (terminal-result {:conflict-input conflict-input
                                        :reason :resolution-commit-failed
                                        :iterations (inc iteration)
                                        :last-attempt-ref worktree-path})))
                  ;; Verify failed: the agent's edits broke tests.
                  ;; Loop again; markers are gone (path set is empty)
                  ;; so recurrence can't fire — let budget-exhausted
                  ;; catch always-failing verify.
                  (recur (inc iteration) prior-paths no-recurrence)))

              ;; Curator: markers-not-resolved. Loop with the new path
              ;; set so recurrence can fire next iteration. Reset the
              ;; consecutive-recurrence counter — this iteration showed
              ;; a different path set than the prior, so the agent is
              ;; making progress (or at least churning differently).
              (= code :curator/markers-not-resolved)
              (recur (inc iteration)
                     (set (curator-conflicted-paths curator))
                     no-recurrence)

              ;; Unknown curator code — surface as a resolution-loop
              ;; fault rather than spinning. The catch-all is intentional;
              ;; new curator error codes added later should land an
              ;; explicit branch above.
              :else
              (terminal-result {:conflict-input conflict-input
                                :reason (or code :curator/unknown-error)
                                :iterations (inc iteration)
                                :last-attempt-ref worktree-path}))))))))

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
