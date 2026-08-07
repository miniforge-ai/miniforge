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
(ns ai.miniforge.dag-executor.protocols.impl.host-guarded.lifecycle
  "Acquire and release steps that hold a task environment to the host-git
   invariants, plus the process-lifetime state they need.

   Split out of `host-guarded` because the executor record sits a layer
   above these steps and its factory a layer above that — four bands in one
   file, which rule 210 answers with a namespace split rather than a
   flattening. `host-guarded` keeps the record and the factory; the policy
   lives here."
  (:require
   [ai.miniforge.dag-executor.host-git-guard :as guard]
   [ai.miniforge.dag-executor.host-git-guard.messages :as msg]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0

;; Process-lifetime state
(def ^{:stratum 0} ^:dynamic *warn-fn*
  "Emit a warning to stderr. Rebind via `binding` in tests to capture
   warnings without stderr noise. Drift is reported both as a result and
   here, because every current caller of `release-environment!` discards
   the result — a silent leak is what this guard exists to end."
  (fn [message] (binding [*out* *err*] (println message))))

;; Both registries are `defonce` so a REPL reload does not drop a verdict
;; already earned, and carry their docs as `:doc` metadata because
;; `defonce` takes no docstring argument.
(defonce ^{:stratum 0} ^:private
  ^{:doc "Checkout path -> the drift report that condemned it.

   Sticky for the life of the process. A checkout whose remotes or
   remote-tracking refs a task run moved is not repaired by finishing the
   run, and every worktree cut from it afterwards inherits the damage, so
   there is no safe point at which to forget."}
  drift-verdicts
  (atom {}))

(defonce ^{:stratum 0} ^:private
  ^{:doc "Environment id -> the snapshot taken when it was acquired.

   `release-environment!` receives only an environment id, so the before
   side of the comparison has to be parked somewhere keyed by that id."}
  acquired-environments
  (atom {}))

(def ^{:stratum 0} ^:private default-repo-path
  "Repo path used when the caller's env-config names none. Matches the
   worktree executor's own default so the guard watches the same checkout
   `git worktree add` will write to."
  ".")

(defn ^{:stratum 0} ^:private canonical-checkout
  "The absolute path of the git common dir `repo-path` resolves to, or the
   path itself when git cannot answer.

   Verdicts are keyed on this rather than the caller's spelling. `\".\"`,
   an absolute path, and any linked worktree of the same checkout all name
   one shared common dir; keying on the raw string would let a verdict
   earned under one spelling miss an acquire made under another."
  [repo-path]
  (let [r (guard/common-dir repo-path)]
    (if (result/ok? r)
      (result/unwrap-or r (str repo-path))
      (str repo-path))))

;------------------------------------------------------------------------------ Layer 1

;; Guarded lifecycle steps
(defn ^{:stratum 1} acquire!
  "Snapshot the checkout, delegate, and park the snapshot against the
   environment id so `release!` has a before side to compare.

   A checkout an earlier run drifted is warned about, loudly and on every
   acquire, but **not refused**. Refusing would be worse than useless in
   `:local` mode: `workflow.runner-environment/build-env-record` collapses
   any non-ok acquire to nil, and `workflow.runner/acquire-environment`
   then runs the pipeline with the caller's original opts — no executor and
   no worktree path — which is the state that namespace's own comment
   describes as what \"let a caller that drove run-pipeline without a
   sandbox leak a real commit into the live worktree\". Turning a refusal
   into a run against the host is the opposite of the intent. Making a
   refusal actually stop the run needs `build-env-record` to tell \"no
   executor configured\" from \"acquire failed\", which is a change in
   another component; see the PR doc's follow-ups.

   A snapshot that cannot be read does fail the acquisition, since it
   propagates through the same collapse and the reads are `git config` and
   `git for-each-ref` against the same path `git worktree add` is about to
   be handed — a path where they fail is a path where the delegate was
   going to fail anyway."
  [delegate task-id env-config]
  (let [repo-path (get env-config :repo-path default-repo-path)
        checkout  (canonical-checkout repo-path)]
    (when-let [verdict (get @drift-verdicts checkout)]
      (*warn-fn* (str (msg/t :guard/host-drifted-before-acquire) " "
                      (pr-str verdict))))
    (-> (guard/snapshot repo-path)
        (result/and-then
         (fn [before]
           (let [acquired (proto/acquire-environment! delegate task-id env-config)
                 env-id   (:environment-id (result/unwrap-or acquired nil))]
             (when (and (result/ok? acquired) env-id)
               (swap! acquired-environments assoc env-id
                      (assoc before :checkout checkout)))
             acquired))))))

(defn ^{:stratum 1} release!
  "Release through the delegate, then compare the checkout against the
   snapshot taken at acquire.

   The delegate runs first and unconditionally: a drifted host is a reason
   to fail the run, never a reason to strand a worktree on disk. On drift
   the verdict is recorded against the checkout, the warning goes to stderr
   for the callers that discard results, and the drift is returned in place
   of the release result.

   A post-release snapshot that cannot be read is treated the same way. It
   means the checkout became unreadable during the run, so whether it
   drifted is unknown — recording a verdict and warning is the fail-closed
   reading, and letting the error through silently would wave through the
   one case where the guard cannot see.

   The environment id carries no run identity beyond itself, so a report
   names the checkout, not the culprit: with tasks running in parallel
   against one checkout, any host mutation between an acquire and its
   release is attributed to whichever run released next."
  [delegate environment-id]
  (letfn [(condemn [checkout report message]
            (swap! drift-verdicts assoc checkout report)
            (*warn-fn* (str message " " (pr-str report)))
            (result/err :host-git-drift message report))]
    (let [before   (get @acquired-environments environment-id)
          released (proto/release-environment! delegate environment-id)]
      (swap! acquired-environments dissoc environment-id)
      (if-not before
        released
        (let [checkout (:checkout before)
              after    (guard/snapshot (:repo-path before))]
          (if-not (result/ok? after)
            (condemn checkout
                     {:repo-path (:repo-path before) :snapshot-failed? true}
                     (msg/t :guard/post-release-snapshot-failed))
            (let [report (guard/drift before (result/unwrap-or after nil))]
              (if (:clean? report)
                released
                (condemn checkout report
                         (msg/t :guard/host-drifted-during-run))))))))))

(defn ^{:stratum 1} reset-state!
  "Drop every parked snapshot and drift verdict.

   Both atoms are `defonce` and process-lifetime by design; tests need a
   clean slate between cases, and an operator who has repaired a condemned
   checkout needs a way to clear its verdict without restarting."
  []
  (reset! acquired-environments {})
  (reset! drift-verdicts {})
  nil)
