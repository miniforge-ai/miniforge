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

(ns ai.miniforge.pr-lifecycle.policy-eval-responder
  "N13 §2.5 Comment Response Agent — orchestrator.

   The deterministic counterpart to `pr-lifecycle.responder` (which
   handles general human review comments via LLM). Targets the one
   bot we own — `miniforge-policy-evaluator[bot]` (the N13 §2.2
   Standards Reviewer) — applies its `:violation/suggested-fix`
   deterministically, commits, pushes, and replies + resolves on
   each fixed thread.

   The work is split across one namespace per stratum (each ~50–150
   lines), this namespace being the orchestration / re-export shim:

     ┌─ policy-eval/payload.clj  (Layer 0: classification)
     ├─ policy-eval/plan.clj     (Layer 1: pure plan)
     ├─ policy-eval/fs.clj       (Layer 2: file I/O)
     ├─ policy-eval/git.clj      (Layer 3a: commit + push)
     └─ policy-eval/reply.clj    (Layer 3b: reply + resolve)

   v0 scope:
   - Single-line `:violation/suggested-fix` (the common case for
     pack-rule violations like 'replace `(get m k default)` with
     `(or (:k m) default)' style hints).
   - Multi-line and patch-hunk-shaped fixes are deferred to v1 with
     a typed `:policy-eval/multi-line-not-supported` skip.
   - Non-fixable comments (`:violation/auto-fixable? false`) are
     escalated as attention items, not auto-applied.

   Pipeline contract (`respond-to-policy-comments!`):
     1. Filter to policy-eval comments only.
     2. plan/plan-fixes → :apply / :escalate / :skip buckets.
     3. fs/materialize-fix! per :to-apply entry → applied + failed.
     4. git/commit-and-push! the applied paths.
     5. reply/reply-and-resolve-fixed! on each applied thread."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.policy-eval.fs :as fs]
   [ai.miniforge.pr-lifecycle.policy-eval.git :as git]
   [ai.miniforge.pr-lifecycle.policy-eval.payload :as payload]
   [ai.miniforge.pr-lifecycle.policy-eval.plan :as plan]
   [ai.miniforge.pr-lifecycle.policy-eval.reply :as reply]))

;; ── re-exports ───────────────────────────────────────────────────────
;;
;; The public surface of the responder is what `pr-lifecycle/interface`
;; (and the test suite) reaches for. Keeping the names accessible at
;; the umbrella namespace lets callers stay decoupled from the internal
;; decomposition — they touch one ns, not five.

(def policy-eval-author      payload/policy-eval-author)
(def policy-eval-comment?    payload/policy-eval-comment?)
(def classify-fix            plan/classify-fix)
(def plan-fixes              plan/plan-fixes)
(def apply-single-line-replacement! fs/apply-single-line-replacement!)
(def materialize-fix!        fs/materialize-fix!)

;; ── orchestrator ─────────────────────────────────────────────────────
;;
;; The whole point of decomposition is here: a top-to-bottom pipeline
;; with each strata's work landing in its own ns, and the orchestrator
;; doing only the assembly. No nested cond, no inline business rules.

(defn- bucket-results
  "Pure: partition `materialize-fix!` results into the three buckets
   the summary needs. `:already-applied` no-ops must NOT enter the
   commit path — they're returned in their own bucket so the caller
   surfaces them in the summary without producing an empty diff."
  [results]
  (let [ok-results      (filter dag/ok? results)
        already-applied? (fn [r] (= :already-applied (-> r :data :status)))
        applied-ok      (remove already-applied? ok-results)
        noop-ok         (filter already-applied? ok-results)]
    {:applied         (mapv :data applied-ok)
     :already-applied (mapv :data noop-ok)
     :failed-to-apply (mapv :error (filter (complement dag/ok?) results))}))

(defn- summary
  "Build the DAG-ok summary map. `git-data` is nil when no commit
   was attempted (zero applied fixes); otherwise it carries
   `{:commit-sha :pushed?}`."
  [{:keys [applied already-applied failed-to-apply]} plan-result git-data replies]
  (dag/ok {:applied         applied
           :already-applied already-applied
           :failed-to-apply failed-to-apply
           :escalated       (:to-escalate plan-result)
           :skipped         (:to-skip plan-result)
           :commit-sha      (:commit-sha git-data)
           :pushed?         (boolean (:pushed? git-data))
           :replies         (or replies [])}))

(defn respond-to-policy-comments!
  "Top-level entry point. See namespace docstring for pipeline
   contract and bucket semantics.

   Returns DAG result with summary:
     {:applied         [<materialize-result>...]
      :already-applied [<materialize-result>...]
      :failed-to-apply [<dag-err>...]
      :escalated       [<plan-entry>...]
      :skipped         [<plan-entry>...]
      :commit-sha      <sha>?
      :pushed?         <bool>?
      :replies         [<dag-result>...]?}"
  [worktree-path pr-number comments]
  (let [policy-comments (filterv payload/policy-eval-comment? comments)
        plan-result     (plan/plan-fixes policy-comments)
        results         (mapv #(fs/materialize-fix! worktree-path %)
                              (:to-apply plan-result))
        buckets         (bucket-results results)
        applied         (:applied buckets)]
    (if (empty? applied)
      (summary buckets plan-result nil nil)
      ;; Railway-binding chain — see clojure-railway-binding rule
      ;; (dewey 211). Push failure short-circuits with the typed
      ;; err result; success body builds the summary.
      (dag/when-let-ok
       [push-r (git/commit-and-push! worktree-path applied)]
       (let [git-data (:data push-r)
             replies  (reply/reply-and-resolve-fixed!
                       worktree-path pr-number (:commit-sha git-data) applied)]
         (summary buckets plan-result git-data replies))))))
