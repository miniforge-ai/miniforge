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

(ns ai.miniforge.cli.main.commands.pr-resume-dispatcher
  "N13 §2.7 Resume Signal Dispatcher CLI.

   Single-pass orchestrator: walk active listeners in the registry,
   query GitHub for each PR's merge state, dispatch resume primers
   for the freshly-merged ones. v0 ships `--once` (single pass).
   The persistent loop is a v1 follow-up — operators can wrap with
   external cron the same way `pr review-monitor` works."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]))

;; ── helpers ──────────────────────────────────────────────────────────

(defn- gh-pr-merge-state
  "Query GitHub for `pr-number`'s merge state in `worktree-path`'s
   repo. Returns `{:state :MERGED|:OPEN|:CLOSED :merge-sha string?
   :merged-at string?}` map on success or nil on gh failure.

   Uses gh's `--json` projection so we get exactly the fields needed
   in one round-trip."
  [worktree-path pr-number]
  (try
    (let [r (process/shell {:dir (str worktree-path)
                            :out :string :err :string :continue true}
                           "gh" "pr" "view" (str pr-number)
                           "--json" "state,mergeCommit,mergedAt")]
      (when (zero? (:exit r))
        (let [parsed (json/parse-string (str/trim (:out r "")) true)]
          {:state     (keyword (:state parsed))
           :merge-sha (get-in parsed [:mergeCommit :oid])
           :merged-at (:mergedAt parsed)})))
    (catch Throwable _ nil)))

(defn- parse-iso-inst
  "Parse an ISO-8601 string from gh into a java.util.Date, or nil."
  [s]
  (try
    (java.util.Date/from (java.time.Instant/parse s))
    (catch Throwable _ nil)))

(defn- merged-pr-record
  "Build the merged-PR record consumed by `dispatch-pr-merge!` from a
   listener entry + the gh response."
  [listener gh-response]
  {:pr/url       (:pr/url listener)
   :merge/sha    (:merge-sha gh-response)
   :merged-at    (or (parse-iso-inst (:merged-at gh-response))
                     (java.util.Date.))
   :diff-summary nil}) ; v0 omits diff-summary; gh diff --stat is a follow-up

(defn- group-active-listeners-by-pr
  "Pure: group `entries-for-agent`-style listener bundle into
   `{pr-url [listener ...]}` filtered to `:active`."
  [registry]
  (->> (:registry/listeners registry)
       (reduce-kv
        (fn [acc pr-url listeners]
          (let [actives (filter #(= :active (:status %)) listeners)]
            (if (seq actives)
              (assoc acc pr-url (vec actives))
              acc)))
        {})))

(defn- print-listener-failure!
  "Render one per-listener failure entry from the dispatch summary's
   `:failed` vector to a typed error line. Pulled out so the
   `doseq` body stays a single named call."
  [failure]
  (display/print-error
   (messages/t :pr/resume-dispatch-listener-failed
               {:lid     (str (:listener/id failure))
                :code    (str (get-in failure [:error :code]))
                :message (or (get-in failure [:error :message]) "")})))

(defn- print-dispatch-result!
  "Render the operator-facing summary of one
   `dispatch-pr-merge!` call. Branches on `(dag/ok? r)` first
   because the dispatcher returns a DAG result (per its public
   contract); only the success branch unwraps `(:data r)` for the
   per-PR summary line + per-listener failure lines."
  [pr-number pr-url r]
  (cond
    (not (dag/ok? r))
    (display/print-error
     (messages/t :pr/resume-dispatch-pr-error
                 {:n       pr-number
                  :url     pr-url
                  :code    (str (get-in r [:error :code]))
                  :message (or (get-in r [:error :message]) "")}))

    :else
    (let [summary (:data r)]
      (display/print-info
       (messages/t :pr/resume-dispatch-pr-summary
                   {:n      pr-number
                    :url    pr-url
                    :ok     (count (:dispatched summary))
                    :failed (count (:failed summary))
                    :total  (:listener-count summary)}))
      (run! print-listener-failure! (:failed summary)))))

(defn- dispatch-merged-pr!
  "For one PR with at least one `:active` listener: query gh, decide
   if merged, dispatch + mark on yes."
  [worktree-path pr-url listeners]
  (let [pr-number (:pr/number (first listeners))
        gh        (gh-pr-merge-state worktree-path pr-number)]
    (cond
      (nil? gh)
      (display/print-error
       (messages/t :pr/resume-dispatch-gh-failed {:n pr-number :url pr-url}))

      (not= :MERGED (:state gh))
      (display/print-info
       (messages/t :pr/resume-dispatch-not-merged
                   {:n pr-number :state (str (:state gh))}))

      :else
      (print-dispatch-result!
       pr-number pr-url
       (pr-lifecycle/dispatch-pr-merge!
        worktree-path pr-url (merged-pr-record (first listeners) gh))))))

(defn- run-pass!
  "One pass: read registry, group active listeners by PR, dispatch each."
  [worktree-path]
  (let [r (pr-lifecycle/read-listener-registry worktree-path)]
    (cond
      (not (dag/ok? r))
      (display/print-error
       (messages/t :pr/resume-dispatch-registry-failed
                   {:code    (str (get-in r [:error :code]))
                    :message (or (get-in r [:error :message]) "")}))

      :else
      (let [groups (group-active-listeners-by-pr (:data r))]
        (display/print-info
         (messages/t :pr/resume-dispatch-pass-start
                     {:pr-count (count groups)}))
        (doseq [[pr-url listeners] groups]
          (dispatch-merged-pr! worktree-path pr-url listeners))))))

;; ── command entry ────────────────────────────────────────────────────

(defn pr-resume-dispatcher-cmd
  "CLI entry for `bb miniforge pr resume-dispatch`.

   v0: single-pass. The persistent loop is a v1 follow-up.

   Flags:
   - --repo <path>  worktree containing `.miniforge/listener-registry.edn`
                    AND a git remote that gh can resolve {owner}/{repo}
                    against (defaults to cwd)
   - --once         single pass (default; v0 has no other mode)"
  [opts]
  (let [worktree-path (get opts :repo (str (fs/cwd)))]
    (cond
      (not (fs/exists? worktree-path))
      (display/print-error
       (messages/t :pr/resume-dispatch-repo-not-found {:path worktree-path}))

      :else
      (try
        (run-pass! worktree-path)
        (catch Exception e
          (display/print-error
           (messages/t :pr/resume-dispatch-failed {:message (ex-message e)})))))))
