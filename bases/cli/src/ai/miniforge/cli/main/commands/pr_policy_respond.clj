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

(ns ai.miniforge.cli.main.commands.pr-policy-respond
  "N13 §2.5 Comment Response Agent CLI — policy-eval (deterministic) path.

   Distinct from `bb miniforge pr respond <url>`, which uses LLM-driven
   fix generation for general human review comments. This command
   targets the one bot we own (`miniforge-policy-evaluator[bot]`) and
   applies its `:violation/suggested-fix` deterministically — no LLM
   call, no operator hand.

   Pipeline shape — every step is a ctx-transformer that either
   passes the ctx through enriched, or attaches `:error {:kind ...}`
   and short-circuits subsequent steps. The terminal step
   `pr-policy-finalize!` reads `ctx` and prints exactly one of:
   error → failure → summary."
  (:require
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]))

;; ── error rendering ──────────────────────────────────────────────────

(defn- error-message
  "Translate the typed `:error` map on `ctx` into the operator-facing
   string. New `:kind`s should land here (and in the messages catalog)
   rather than re-stringifying at call sites."
  [{:keys [error]}]
  (case (:kind error)
    :usage           (messages/t :pr/policy-respond-usage)
    :bad-url         (messages/t :pr/policy-respond-bad-url)
    :checkout-failed (messages/t :pr/policy-respond-checkout-failed {:n (:n error)})
    :fetch-failed    (messages/t :pr/policy-respond-fetch-failed    {:n (:n error)})))

(defn- print-summary!
  [pr-number r]
  (let [d (:data r)
        applied   (count (:applied d))
        failed    (count (:failed-to-apply d))
        escalated (count (:escalated d))
        skipped   (count (:skipped d))]
    (display/print-info
     (messages/t :pr/policy-respond-summary
                 {:n        pr-number
                  :applied  applied
                  :failed   failed
                  :escalated escalated
                  :skipped  skipped
                  :commit   (or (:commit-sha d) "—")}))
    (doseq [e (:escalated d)]
      (display/print-info
       (messages/t :pr/policy-respond-escalated
                   {:cid (str (-> e :comment :comment/id))
                    :reason (str (:reason e))})))))

(defn- print-failure!
  [pr-number r]
  (display/print-error
   (messages/t :pr/policy-respond-failed
               {:n pr-number
                :code (str (get-in r [:error :code]))
                :message (or (get-in r [:error :message]) "")})))

;; ── infra helpers (worktree-scoped shell ops) ────────────────────────

(defn- checkout-pr!
  "Run `gh pr checkout <pr-number>` in `worktree-path`. Returns the
   current branch on success, nil on failure."
  [worktree-path pr-number]
  (try
    (let [r (process/shell {:dir (str worktree-path)
                            :out :string :err :string :continue true}
                           "gh" "pr" "checkout" (str pr-number))]
      (when (zero? (:exit r))
        (let [b (process/shell {:dir (str worktree-path)
                                :out :string :err :string :continue true}
                               "git" "branch" "--show-current")]
          (when (zero? (:exit b))
            (str/trim (:out b ""))))))
    (catch Throwable _ nil)))

(defn- fetch-comments
  "Fetch the raw PR comments via the pr-lifecycle interface. Returns
   the comments vector or nil on failure."
  [worktree-path pr-number]
  (let [r (pr-lifecycle/fetch-pr-comments worktree-path pr-number)]
    (when (dag/ok? r)
      (get-in r [:data :comments]))))

;; ── pipeline steps ───────────────────────────────────────────────────
;;
;; Each step takes ctx, returns ctx. If ctx already carries `:error`
;; the step is a no-op (early-bail) so downstream stages don't need
;; per-step guards. Names match the operator's pipeline-style example.

(defn- pr-policy-parse-url!
  "Step 1: parse the operator-supplied URL, derive the PR number."
  [{:keys [opts error] :as ctx}]
  (if error
    ctx
    (let [url (:url opts)]
      (cond
        (or (nil? url) (str/blank? url))
        (assoc ctx :error {:kind :usage})

        :else
        (let [{:keys [number]} (pr-lifecycle/parse-pr-url url)]
          (if-not number
            (assoc ctx :error {:kind :bad-url})
            (assoc ctx :url url :number number)))))))

(defn- pr-policy-checkout!
  "Step 2: switch the working tree to the PR branch via `gh pr checkout`."
  [{:keys [error number] :as ctx}]
  (if error
    ctx
    (let [cwd (System/getProperty "user.dir")]
      (display/print-info (messages/t :pr/policy-respond-checkout {:n number}))
      (if-let [branch (checkout-pr! cwd number)]
        (assoc ctx :cwd cwd :branch branch)
        (assoc ctx :error {:kind :checkout-failed :n number})))))

(defn- pr-policy-fetch-comments!
  "Step 3: pull every PR comment via pr-lifecycle."
  [{:keys [error cwd number branch] :as ctx}]
  (if error
    ctx
    (do
      (display/print-info (messages/t :pr/policy-respond-on-branch {:branch branch}))
      (if-let [comments (fetch-comments cwd number)]
        (assoc ctx :comments comments)
        (assoc ctx :error {:kind :fetch-failed :n number})))))

(defn- pr-policy-respond!
  "Step 4: hand off to the deterministic responder. Its DAG-result
   becomes `:result` on the ctx — the finalize step decides whether
   to render summary or failure."
  [{:keys [error cwd number comments] :as ctx}]
  (if error
    ctx
    (assoc ctx :result
           (pr-lifecycle/respond-to-policy-comments! cwd number comments))))

(defn- pr-policy-finalize!
  "Step 5: terminal render. Three exclusive branches in order:
   ctx-level error, responder-level error, success summary."
  [{:keys [error number result] :as ctx}]
  (cond
    error               (display/print-error (error-message ctx))
    (not (dag/ok? result)) (print-failure! number result)
    :else               (print-summary! number result))
  ctx)

;; ── command entry ────────────────────────────────────────────────────

(defn pr-policy-respond-cmd
  "CLI entry for `bb miniforge pr policy-respond <pr-url>`.

   Pipeline: parse-url → checkout → fetch-comments → respond → finalize.
   Any step may attach `:error {:kind ... :n? ...}` to the ctx — later
   steps then short-circuit and `pr-policy-finalize!` prints exactly
   one message per invocation. Unhandled throws are caught at the
   outer try and rendered as the generic `pr/policy-respond-failed`
   message with `:code \"exception\"`."
  [opts]
  (try
    (-> {:opts opts}
        pr-policy-parse-url!
        pr-policy-checkout!
        pr-policy-fetch-comments!
        pr-policy-respond!
        pr-policy-finalize!)
    (catch Exception e
      (display/print-error
       (messages/t :pr/policy-respond-failed
                   {:n "?"
                    :code "exception"
                    :message (ex-message e)})))))
