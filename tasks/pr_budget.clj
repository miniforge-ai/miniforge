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
(ns pr-budget
  "PR-level counterpart to `commit-budget` — a hard ceiling on the
   WHOLE pull request's cumulative diff size, not just its latest
   commit.

   Why this exists as a separate gate: `commit-budget` runs at commit
   time on the *staged* diff only. A single-commit PR is fully covered
   by it, but nothing previously stopped a PR from packaging one
   mechanical whole-file rewrite into exactly one commit that itself
   sits at (or is overridden past) the 200-line ceiling — 74- and
   98-file, 10k+-line PRs shipped this way during the stratum-lint
   Wave 1 program, each as a single overridden commit. Copilot's
   automated review silently declines to comment at all past its own
   size threshold on PRs that large, so the only review such a PR
   actually received was whatever a human or agent did by hand,
   after the fact. This gate runs in CI against the merge-base..head
   diff so it catches the PR's total shape regardless of how many
   commits it took to get there.

   Reuses `commit-budget`'s pure line-counting/exclusion logic
   (comment/blank stripping, generated-path exclusions) so a file that
   doesn't count against a commit doesn't count against its PR either.

   Default: 600 lines -- deliberately NOT the same 200 as
   `commit-budget`. The 200-line commit ceiling is Smartbear 2010 /
   Cisco 2018 human review-throughput research; there is no equivalent
   published data for LLM-driven review, and this codebase is
   overwhelmingly agent-written and agent-reviewed, not human-authored
   commit-by-commit. 600 is a reasoned estimate, not a citation: three
   times the commit ceiling (a PR bundling a small, coherent handful of
   commit-sized steps, not a monolith), an order of magnitude above the
   human number (agents don't suffer literal per-line reading fatigue
   the way human reviewers do), and two orders of magnitude below what
   actually broke down -- during the stratum-lint Wave 1 program,
   GitHub Copilot's automated review engaged fully at 9,906 raw changed
   lines but silently declined to comment at all at 12,592, and a
   dedicated adversarial review pass at that same scale cost 170-210k
   tokens and still needed follow-up rounds to reach a clean state.
   Expect this number to move as real data accumulates against it, the
   same way `commit-budget`'s 200 is treated as calibrated, not fixed
   forever.

   Override: add a line matching `MINIFORGE_PR_BUDGET_OVERRIDE: <rationale>`
   to the PR description. Unlike the commit-level env-var override
   (which only the person committing can see), this is visible to
   every reviewer on the PR itself."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [commit-budget :as cb]))

;------------------------------------------------------------------------------ Layer 0

;; pure helpers (no in-namespace deps)
(def ^{:stratum 0} default-budget
  "PR-level ceiling — deliberately NOT `commit-budget/default-budget`.
   See the namespace docstring for the full reasoning; short version:
   this repo's PRs are agent-authored and agent-reviewed, not the
   human-commit-by-commit case the 200-line number was calibrated for."
  600)

(def ^{:stratum 0} override-marker-re
  "Matches `MINIFORGE_PR_BUDGET_OVERRIDE: <rationale>` (or `=`) anywhere
   in the PR description, case-sensitive on the marker itself so it
   can't be triggered by incidental prose. Captures the rationale.
   Tolerates leading whitespace and a single markdown list/quote/emphasis
   prefix (`-`, `*`, `>`, `_`) before the marker, since GitHub's editor
   readily produces `  MINIFORGE_PR_BUDGET_OVERRIDE: ...` (indented) or
   `> MINIFORGE_PR_BUDGET_OVERRIDE: ...` (blockquoted) without the
   author intending anything other than a plain override line."
  #"(?m)^\s*[-*>_]?\s*MINIFORGE_PR_BUDGET_OVERRIDE\s*[:=]\s*(.+)$")

;; git wrapper (composes Layer 0 + commit-budget's pure parse/count fns)
(defn ^{:stratum 0} pr-diff
  "Parsed `git diff --no-color -U0 <base>...<head>` (three-dot: against
   the merge-base, matching what GitHub's own PR diff view and `gh pr
   diff` report) as per-file diff maps, reusing `commit-budget`'s
   parser. Fails the task closed on a non-zero git exit, same posture
   as `commit-budget/staged-diff` — an unreadable diff should never
   silently pass."
  [base-sha head-sha]
  (let [{:keys [out err exit]}
        (p/sh {:out :string :err :string}
              "git" "diff" "--no-color" "-U0" (str base-sha "..." head-sha))]
    (cond
      (zero? exit) (cb/parse-diff out)
      :else
      (do (binding [*out* *err*]
            (println (format "❌ pr-budget: `git diff` %s...%s exited %d"
                              base-sha head-sha exit))
            (when-not (str/blank? err)
              (println (str/trim err))))
          (System/exit 1)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} override-rationale
  "Rationale string when `pr-body` contains the override marker, else
   nil. `pr-body` may be nil (e.g. an empty PR description)."
  [pr-body]
  (when pr-body
    (some-> (re-find override-marker-re pr-body)
            second
            str/trim
            not-empty)))

;------------------------------------------------------------------------------ Layer 2

;; CLI entry (composes Layer 2)
(defn ^{:stratum 2} check-pr-budget!
  "PR-level gate. `base-sha`/`head-sha` are the PR's base and head
   commits (three-dot diff against their merge-base). `pr-body` is the
   PR description text, used only to look for the override marker.

   Returns nil when within budget or overridden; calls `(System/exit
   1)` on a genuine over-budget PR so this composes as a required CI
   check."
  [base-sha head-sha pr-body budget]
  (let [entries   (pr-diff base-sha head-sha)
        annotated (cb/reportable-changes entries)
        total     (cb/total-lines annotated)
        rationale (override-rationale pr-body)]
    (cond
      (empty? entries)
      (println "📏 pr-budget: no changed files in this diff")

      rationale
      (do (println (format "📏 pr-budget: OVERRIDDEN (%d reportable lines)" total))
          (println (str "  rationale: " rationale))
          (when (> total budget)
            (println (format "  WARNING: %d > %d — budget intentionally bypassed."
                              total budget)))
          (cb/print-report! annotated total))

      (<= total budget)
      (println (format "📏 pr-budget: %d / %d lines OK" total budget))

      :else
      (do (println "")
          (println "════════════════════════════════════════════════════════════════")
          (println (format "❌ PR BUDGET EXCEEDED: %d > %d lines" total budget))
          (println "════════════════════════════════════════════════════════════════")
          (println "")
          (cb/print-report! annotated total)
          (println "")
          (println "Why this exists:")
          (println "  GitHub Copilot's automated review silently declines to comment")
          (println "  at all on PRs past its own size threshold (empirically somewhere")
          (println "  between 9,906 and 12,592 raw changed lines) -- past that point a")
          (println "  gargantuan PR gets effectively zero automated review coverage.")
          (println "  Even a dedicated adversarial review pass at that scale costs")
          (println "  170-210k tokens and still needs follow-up rounds to converge.")
          (println "")
          (println "How to proceed:")
          (println "  • Split the change into several smaller PRs -- e.g. one")
          (println "    mechanical fix per file or small file-group, not one PR per")
          (println "    whole component.")
          (println "  • Genuine exception: add a line to the PR description reading")
          (println "    `MINIFORGE_PR_BUDGET_OVERRIDE: <rationale>` and re-run this")
          (println "    check (re-push or re-run the workflow).")
          (println "")
          (System/exit 1)))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} run
  "CI entry point. Reads `PR_BASE_SHA`/`PR_HEAD_SHA`/`PR_BODY` from the
   environment (set by the calling GitHub Actions step from the
   `pull_request` event payload) rather than taking CLI args, so the
   workflow YAML stays a plain `bb pr-budget` invocation (the bb.edn
   task, not `bb -m pr-budget` -- this namespace has no `-main`)."
  [_opts]
  (let [base (System/getenv "PR_BASE_SHA")
        head (System/getenv "PR_HEAD_SHA")]
    (if (or (str/blank? base) (str/blank? head))
      (do (binding [*out* *err*]
            (println "❌ pr-budget: PR_BASE_SHA / PR_HEAD_SHA not set -- this task only runs under the pull_request CI job"))
          (System/exit 1))
      (check-pr-budget! base head (System/getenv "PR_BODY") default-budget))))
