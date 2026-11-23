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
(ns ai.miniforge.phase-software-factory.release-state
  "What the release reads from the run before it prepares: the workflow
   state handed to the release executor, the skip probe that decides
   whether there is anything to release, and the verify/review readiness
   the :release-ready gate reads.

   Split out of `release` (rule 210); the definitions moved unchanged."
  (:require [ai.miniforge.workspace.interface :as workspace]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-software-factory.messages :as messages]
            [ai.miniforge.phase-software-factory.release-worktree :as release-worktree]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} log-already-implemented!
  "Log when implement phase reports work already done."
  [ctx impl-status]
  (when (= :already-implemented impl-status)
    (let [logger (or (get-in ctx [:execution/logger])
                     (log/create-logger {:min-level :info :output :human}))]
      (log/info logger :release :release/skipped-already-implemented
                {:data {:summary (get-in ctx [:execution/phase-results :implement :result :summary])}}))))

;; assert-implement-artifact! used to throw when the implement phase
;; reported anything other than :status :success. That gate disagreed with
;; verify and review, which read the environment directly and run regardless
;; of what the implement result map said. The disagreement surfaced under
;; the curator's :curator/no-files-written path: the agent narrates code in
;; chat, curator marks implement :failed, but persist still captures whatever
;; landed on disk and verify+review work fine against it. Release would
;; then explode with `:release/no-implement-artifact` even though there was
;; real work to release. Removed — the env-based `:release/zero-files`
;; check below is the authoritative gate, matching what verify and review
;; already do.
(defn- ^{:stratum 0} ctx-worktree-path
  "Resolve the working directory from phase context."
  [ctx]
  (workspace/resolve-execution-workdir ctx "release"))

(defn- ^{:stratum 0} capsule-target
  "The capsule a governed run executes in, as {:execute-fn :executor
   :env-id :workdir}, or nil for a host run. `:workdir` is the capsule's own
   checkout (`:execution/capsule-workdir`): the host worktree path
   `worktree-path` does not exist inside the capsule."
  [ctx worktree-path]
  (let [execute-fn (get ctx :execution/execute-fn)
        executor   (get ctx :execution/executor)
        env-id     (get ctx :execution/environment-id)]
    (when (and (= :governed (get ctx :execution/mode)) execute-fn executor env-id)
      {:execute-fn execute-fn
       :executor   executor
       :env-id     env-id
       ;; The runner sets the key on every run, nil without a capsule
       ;; checkout, so a get default would never apply.
       :workdir    (or (get ctx :execution/capsule-workdir) worktree-path)})))

(defn- ^{:stratum 0} rehydrate-committed-files
  "Read content for the `:code/file-paths` recorded by the implement
   phase. Used when implement's boundary commit has cleaned the
   worktree — `git-dirty-files` then returns empty even though the
   files exist as committed content on the task branch.

   Modeled after review.clj/rehydrate-from-paths but with two
   differences:
   - Returns a `:code/files` vector directly (review wraps it back
     into the outer artifact); release wants the vector to feed
     into `code-artifacts` below.
   - Applies the same `agent/substantive-file?` filter that
     `git-dirty-files` uses, so a record consisting only of
     `.miniforge/session-id` does not count as releasable work
     (matches the iter-23 empty-diff PR guard).

   Host-mode only: `agent/rehydrate-files` reads the host
   filesystem. In governed (capsule) mode the worktree lives
   inside a Docker/K8s container and the host cannot reach it
   directly. A capsule-aware variant is a follow-up; today the
   capsule path falls through to `git-dirty-files-capsule`, which
   matches the pre-fix behavior in that mode."
  [implement-artifact worktree-path]
  (when-let [paths (and implement-artifact
                        worktree-path
                        (seq (:code/file-paths implement-artifact)))]
    (let [files (try
                  (agent/rehydrate-files worktree-path paths
                                         (:code/file-actions implement-artifact))
                  (catch Exception _ nil))]
      (->> (or files [])
           (filter agent/substantive-file?)
           vec))))

(defn- ^{:stratum 0} unresolved-known-issues
  "The review's unresolved non-blocking issues to record on the PR. Prefer the
   structured `:review/issues` (severity :warning/:nit — these carry file/line);
   fall back to the legacy `:review/warnings` description strings when no
   structured issues are present (gate-produced warnings arrive as strings)."
  [review-output]
  (let [structured (filterv (comp #{:warning :nit} :severity)
                            (get review-output :review/issues []))]
    (if (seq structured)
      structured
      (vec (get review-output :review/warnings [])))))

(defn- ^{:stratum 0} readiness-check
  "One phase's readiness evidence: verify completed, or review completed
   with an approving decision. A phase with no recorded result fails."
  [ctx phase-name]
  (let [phase-result (get-in ctx [:execution/phase-results phase-name])
        completed?   (and (some? phase-result) (phase/succeeded? phase-result))
        decision     (when (= :review phase-name)
                       (response/review-decision (response/phase-output phase-result)))]
    (cond-> {:phase   phase-name
             :passed? (and completed?
                           (or (not= :review phase-name)
                               (contains? #{:approved :conditionally-approved} decision)))}
      (nil? phase-result)     (assoc :reason :no-result)
      (= :review phase-name)  (assoc :decision decision))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} release-readiness
  "Whether the release may publish: each of verify and review that the
   workflow pipeline configures must have passed. A pipeline configuring
   neither has no evidence and is not ready. The :release-ready gate reads
   this from the phase output's :release/readiness."
  [ctx]
  (let [configured (set (map :phase (get-in ctx [:execution/workflow :workflow/pipeline])))
        checks     (mapv #(readiness-check ctx %) (filter configured [:verify :review]))]
    {:ready? (boolean (and (seq checks) (every? :passed? checks)))
     :checks (if (seq checks) checks [{:phase :verify :passed? false :reason :not-configured}])}))

(defn ^{:stratum 1} skip-probe
  "The scan enter-release skips on: the worktree's dirty files, or when it
   has none, the files the task branch committed beyond its base. An
   implement that reports :already-implemented after an earlier iteration
   committed its work leaves a clean worktree, and that work must still be
   released. A failed scan is returned as its anomaly, which never reads as
   \"no changes\": build-workflow-state raises it unless implement's
   recorded files carry the release."
  [ctx]
  (let [wt      (ctx-worktree-path ctx)
        capsule (capsule-target ctx wt)
        dirty   (release-worktree/dirty-scan wt capsule)]
    (if (and (vector? dirty) (empty? dirty))
      (release-worktree/committed-since-base ctx wt capsule)
      dirty)))

(defn ^{:stratum 1} build-workflow-state
  "Build workflow state from phase context for the release executor.

   In the new environment model, code changes live in the execution
   environment's git working tree (:execution/worktree-path) rather than
   being serialized into phase results.

   File-discovery resolution order:

     1. `:code/file-paths` recorded on the implement phase artifact
        (`lightweight-curated-artifact`). After implement's boundary
        commit lands on the task branch the worktree is clean at HEAD,
        so `git-dirty-files` returns empty even though the files exist
        as committed content. Reading the recorded paths off disk is
        the same pattern review.clj/rehydrate-from-paths uses.
     2. `git-dirty-files` (or its capsule-aware twin) for files the
        agent wrote to the worktree but the boundary did not commit
        (legacy / partial-write path).
     3. Throw :release/zero-files when both paths come back empty —
        the only legitimate \"nothing to release\" condition. When the
        git scan itself fails, throw its :release/git-status-failed
        instead, carrying git's error.

   `probe` is the scan enter-release already ran to decide whether to
   skip (nil when it ran none): dirty files, else files committed beyond
   the task's base. It stands in for step 2's rescan, so a failed probe
   raises its own error instead of a clean rescan turning it into
   zero-files.

   Stage-3 dogfood (2026-05-07) tripped on the missing #1 path:
   plan/implement/verify/review all green, files committed on the
   task branch, but release threw zero-files because git-dirty-files
   saw a clean worktree. This commit closes that gap."
  [ctx probe]
  (let [impl-result   (get-in ctx [:execution/phase-results :implement :result])
        impl-artifact (get-in ctx [:execution/phase-results :implement :artifact])
        impl-status   (:status impl-result)]
    (log-already-implemented! ctx impl-status)
    (let [worktree-path  (ctx-worktree-path ctx)
          capsule        (capsule-target ctx worktree-path)
          committed      (rehydrate-committed-files impl-artifact worktree-path)
          dirty          (when (empty? committed)
                           (if (some? probe)
                             probe
                             (release-worktree/dirty-scan worktree-path capsule)))
          files          (release-worktree/release-files! committed dirty impl-result worktree-path)
          code-artifacts [{:artifact/type    :code
                           :artifact/content {:code/id        (random-uuid)
                                              :code/language  "mixed"
                                              :code/files     files
                                              :code/summary   (get impl-result :summary
                                                                   (messages/t :release/changes-description))
                                              :environment-id (:environment-id impl-result)}}]
          ;; Carry the review phase's unresolved non-blocking warnings into the
          ;; release as a :review artifact, so an :accept-with-warnings PR opens
          ;; with those warnings recorded as known-issues (release-executor's
          ;; PR-body renderer reads :review-typed artifacts). Pass-through only:
          ;; presentation lives in the renderer. Clean reviews carry no warnings
          ;; → no :review artifact → no change to the happy-path PR body.
          review-output  (get-in ctx [:execution/phase-results :review :result :output])
          known-issues   (unresolved-known-issues review-output)
          review-artifacts (when (seq known-issues)
                             [{:artifact/type    :review
                               :artifact/content {:review/decision (:review/decision review-output)
                                                  :review/summary  (:review/summary review-output)
                                                  :review/warnings known-issues}}])
          input          (get-in ctx [:execution/input])]
      {:workflow/id       (or (get-in ctx [:execution/id]) (random-uuid))
       :workflow/phase    :release
       :workflow/spec     {:spec/description (or (:description input)
                                                 (:title input)
                                                 (messages/t :default/task-description))}
       :workflow/artifacts (into code-artifacts review-artifacts)})))
