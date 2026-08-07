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
(ns ai.miniforge.pr-lifecycle.merge-transaction
  "PR merge as a transacted effect (Ariadne step 2d, part 1).

   What this fixes. `merge-pr!` appends `--auto` UNCONDITIONALLY, so a
   zero exit means auto-merge was ENABLED, not that anything merged.
   The old path read that as `{:merged? true}` and published a merged
   event carrying a SHA from `git rev-parse HEAD` in the local worktree
   — the branch tip, not the squash commit on base. Two claims the code
   could not substantiate.

   Here, enabling auto-merge is recorded as what it is: a request
   accepted, outcome not yet known. GitHub is then asked. A merged event
   is published only against an observed merge, carrying the mergeCommit
   GitHub reports. When the answer is 'not yet', the record stays
   reconcilable and a later pass asks again.

   The caller supplies the runtime-issued grant and allowing envelope.
   Their identifiers are durable on the proposal, and the effect
   transaction rechecks the grant against that durable scope before the
   provider command can run."
  (:require
   [ai.miniforge.effect-transaction.interface :as fx]
   [cheshire.core :as json])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

;; Configuration
(def ^{:stratum 0} default-store-dir-property
  "Where merge transaction records live when the caller names no store.

   Deliberately an explicit path under the miniforge home rather than a
   relative one: a durability record written to whatever directory the
   process happened to start in is an audit trail nobody can find."
  ".miniforge/effects")

(defn ^{:stratum 0} merged-state?
  "True when GitHub reports the PR as merged. GitHub is the authority on
   whether a merge happened; nothing local is."
  [observed]
  (= "MERGED" (:pr/state observed)))

;; Asking GitHub
(defn ^{:stratum 0} parse-pr-view
  "Parse `gh pr view --json state,mergeCommit` output into the observed
   shape. A response we cannot parse yields nil — treated upstream as
   'did not answer', never as 'not merged'."
  [json-str]
  (try
    (let [m (json/parse-string json-str true)]
      (when (:state m)
        {:pr/state (:state m)
         :merge/sha (get-in m [:mergeCommit :oid])}))
    (catch Exception _ nil)))

(defn ^{:stratum 0} substantiated-sha
  "The merge SHA from a settled record, or nil.

   Two paths substantiate a SHA: a commit whose immediate probe already
   saw MERGED (`:succeeded`), and a later `reconcile!` that observed one
   (`:effect/matched?`). Everything else — pending, failed, unresolved,
   or an anomaly — yields nil.

   nil is the correct answer for an unmerged or unresolved transaction:
   a SHA the code cannot substantiate does not get published, which is
   the specific habit this namespace exists to break."
  [t]
  (when (and (map? t)
             (or (= :succeeded (:effect/state t))
                 (:effect/matched? t)))
    (get-in t [:effect/observed :merge/sha])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} store-dir
  "Resolve the effect store from `context`, falling back to the
   miniforge home. Never relative to the process working directory."
  [context]
  (or (:effect-store-dir context)
      (str (System/getProperty "user.home") "/" default-store-dir-property)))

(defn ^{:stratum 1} probe
  "Ask GitHub what actually happened to this PR. `run-gh` is injected so
   callers and tests supply the shell; it returns `{:ok? bool :output s}`.

   Returns the `reconcile!` answer shape, or nil when GitHub did not
   answer — which leaves the record unresolved rather than asserting a
   negative we did not observe."
  [run-gh pr-number]
  (let [{:keys [ok? output]} (run-gh ["gh" "pr" "view" (str pr-number)
                                      "--json" "state,mergeCommit"])]
    (when ok?
      (when-let [observed (parse-pr-view output)]
        {:effect/observed observed
         :effect/matched? (merged-state? observed)}))))

;------------------------------------------------------------------------------ Layer 2

;; The transaction
(defn ^{:stratum 2} propose!
  "Record correlated merge authority and intent before any gh command runs."
  [context authority now]
  (fx/propose! (store-dir context)
               {:effect-id (:effect/id authority)
                :effect-class :effect/merge
                :grant-id (get-in authority [:authority/grant :grant/id])
                :envelope-id (get-in authority [:authority/envelope :envelope/id])
                :proposal (:effect/proposal authority)}
               now))

(defn ^{:stratum 2} commit!
  "Enable auto-merge, then ask GitHub whether it actually merged.

   The outcome mapping is the honest part. Auto-merge accepted but not
   yet completed is `:accepted`, which the coordinator maps to
   `:unknown-outcome` — a request in flight is not a success. Only an
   observed MERGED becomes `:succeeded`, and it carries the SHA GitHub
   reported, never a local one.

   `enable!` performs the gh merge call and returns `{:ok? bool}`;
   `run-gh` answers the follow-up probe. Both injected."
  ([context t pr-number ^Instant now enable! run-gh]
   (commit! context t :authority/unenforced pr-number now enable! run-gh))
  ([context t grant-record pr-number ^Instant now enable! run-gh]
   (fx/commit! (store-dir context) t grant-record {} now
               (fn []
                 (let [{:keys [ok? error]} (enable!)]
                   (if-not ok?
                     {:effect/outcome :failed :effect/failure (str error)}
                     (if-let [answer (probe run-gh pr-number)]
                       (if (:effect/matched? answer)
                         {:effect/outcome :succeeded
                          :effect/observed (:effect/observed answer)}
                         {:effect/outcome :accepted
                          :effect/observed (:effect/observed answer)})
                       {:effect/outcome :accepted})))))))

(defn ^{:stratum 2} reconcile!
  "Ask GitHub again about a record whose outcome is still unknown.

   Returns the settled record when GitHub answered, or the coordinator's
   `:unavailable` anomaly when it did not — in which case the record is
   untouched and the next pass asks again. Callers publish a merged
   event only when the returned record reports `:effect/matched?` true."
  [context t pr-number ^Instant now run-gh]
  (fx/reconcile! (store-dir context) t (fn [_] (probe run-gh pr-number)) now))
