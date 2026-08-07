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

(defn ^{:stratum 0} ^:private drift-message
  "Name the drift by its worse half. A repointed remote outranks a moved
   ref: it changes where every subsequent push and fetch goes, and it is
   how a GitHub token ends up persisted in the host's config."
  [report]
  (if (seq (:remote-url-drift report))
    (msg/t :drift/remote-url-changed)
    (msg/t :drift/remote-ref-rewound)))

;------------------------------------------------------------------------------ Layer 1

;; Guarded lifecycle steps
(defn ^{:stratum 1} acquire!
  "Refuse the acquisition outright when this checkout already has a drift
   verdict; otherwise snapshot it, delegate, and park the snapshot.

   A snapshot that cannot be read fails the acquisition rather than
   proceeding unwatched. That costs nothing in practice: the reads are
   `git config` and `git for-each-ref` against the same path
   `git worktree add` is about to be handed, so a path where they fail is a
   path where the delegate was going to fail anyway."
  [delegate task-id env-config]
  (let [repo-path (get env-config :repo-path default-repo-path)]
    (if-let [verdict (get @drift-verdicts (str repo-path))]
      (result/err :host-git-drift
                  (msg/t :guard/host-drifted-before-acquire)
                  verdict)
      (-> (guard/snapshot repo-path)
          (result/and-then
           (fn [before]
             (let [acquired (proto/acquire-environment! delegate task-id env-config)]
               (when (result/ok? acquired)
                 (swap! acquired-environments
                        assoc
                        (:environment-id (result/unwrap-or acquired nil))
                        before))
               acquired)))))))

(defn ^{:stratum 1} release!
  "Release through the delegate, then compare the checkout against the
   snapshot taken at acquire.

   The delegate runs first and unconditionally: a drifted host is a reason
   to fail the run, never a reason to strand a worktree on disk. On drift
   the verdict is recorded so later acquisitions against this checkout are
   refused, the warning goes to stderr for the callers that discard results,
   and the drift is returned in place of the release result."
  [delegate environment-id]
  (let [before   (get @acquired-environments environment-id)
        released (proto/release-environment! delegate environment-id)]
    (swap! acquired-environments dissoc environment-id)
    (if-not before
      released
      (-> (guard/snapshot (:repo-path before))
          (result/and-then
           (fn [after]
             (let [report (guard/drift before after)]
               (if (:clean? report)
                 released
                 (do
                   (swap! drift-verdicts assoc (:repo-path before) report)
                   (*warn-fn* (str (msg/t :guard/host-drifted-during-run) " "
                                   (pr-str report)))
                   (result/err :host-git-drift
                               (drift-message report)
                               report))))))))))

(defn ^{:stratum 1} reset-state!
  "Drop every parked snapshot and drift verdict.

   Both atoms are `defonce` and process-lifetime by design; tests need a
   clean slate between cases, and an operator who has repaired a condemned
   checkout needs a way to clear its verdict without restarting."
  []
  (reset! acquired-environments {})
  (reset! drift-verdicts {})
  nil)
