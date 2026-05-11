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
   call, no operator hand."
  (:require
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]))

;; ── helpers ──────────────────────────────────────────────────────────

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
  "Fetch the raw PR comments via the existing `pr-poller`. Returns
   the comments vector or nil on failure."
  [worktree-path pr-number]
  (let [poller-fetch (requiring-resolve 'ai.miniforge.pr-lifecycle.pr-poller/fetch-pr-comments)
        r (poller-fetch worktree-path pr-number)]
    (when (dag/ok? r)
      (get-in r [:data :comments]))))

(defn- print-summary
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

(defn- print-failure
  [pr-number r]
  (display/print-error
   (messages/t :pr/policy-respond-failed
               {:n pr-number
                :code (str (get-in r [:error :code]))
                :message (or (get-in r [:error :message]) "")})))

;; ── command entry ────────────────────────────────────────────────────

(defn pr-policy-respond-cmd
  "CLI entry for `bb miniforge pr policy-respond <pr-url>`.

   Steps:
   1. Parse the URL, derive PR number.
   2. `gh pr checkout <n>` to switch the worktree to the PR branch.
   3. Fetch all comments via `pr-poller/fetch-pr-comments`.
   4. Filter / plan / apply / commit / push / reply via
      `pr-lifecycle/respond-to-policy-comments!`.
   5. Print per-class counts to operator."
  [opts]
  (try
    (let [{:keys [url]} opts]
      (cond
        (or (nil? url) (str/blank? url))
        (display/print-error (messages/t :pr/policy-respond-usage))

        :else
        (let [parse-url (requiring-resolve 'ai.miniforge.pr-lifecycle.interface/parse-pr-url)
              {:keys [number]} (parse-url url)]
          (cond
            (not number)
            (display/print-error (messages/t :pr/policy-respond-bad-url))

            :else
            (let [cwd (System/getProperty "user.dir")
                  _ (display/print-info (messages/t :pr/policy-respond-checkout {:n number}))
                  branch (checkout-pr! cwd number)]
              (cond
                (nil? branch)
                (display/print-error (messages/t :pr/policy-respond-checkout-failed {:n number}))

                :else
                (do
                  (display/print-info (messages/t :pr/policy-respond-on-branch {:branch branch}))
                  (let [comments (fetch-comments cwd number)]
                    (cond
                      (nil? comments)
                      (display/print-error (messages/t :pr/policy-respond-fetch-failed {:n number}))

                      :else
                      (let [r (pr-lifecycle/respond-to-policy-comments! cwd number comments)]
                        (if (dag/ok? r)
                          (print-summary number r)
                          (print-failure number r))))))))))))
    (catch Exception e
      (display/print-error
       (messages/t :pr/policy-respond-failed
                   {:n "?"
                    :code "exception"
                    :message (ex-message e)})))))
