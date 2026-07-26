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
(ns stratum
  "Stratum-lint autofix mechanics, split out of `lint` (rule 210: a
   namespace wanting a fourth real layer is the signal to split it).
   `lint/stratum-staged` is the dispatcher; it calls `autofix-and-restage!`
   for fully-staged files or `lint-only-and-fail!` for partially-staged
   ones. `autofix-and-restage!` composes `restage!` and `post-fix-lint!`,
   which both read `stratum-lint-deps` — the genuine 3-deep layer chain
   that no longer fits alongside the dispatcher in one file."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private stratum-lint-deps
  "Sha-pinned git coordinate for the stratum-lint Clojure component
   (bb-native; the linter for stratified-design separator comments whose
   heading ends in `Layer N`, miniforge-standards rule 210). Resolved lazily in a
   subprocess via `bb -Sdeps` so plain `bb <task>` invocations — CI
   included — never fetch the sibling repo; only the pre-commit gate
   pays the one-time clone."
  (pr-str {:deps {'io.github.miniforge-ai/stratum-lint
                  {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd"
                   :deps/root "clojure"}}}))

(defn ^{:stratum 0} restage!
  "Re-stage `files` after autofix; fails the commit if `git add` itself
   fails, rather than silently leaving the fixed content unstaged."
  [files]
  (doseq [f files]
    (let [{:keys [exit err]} (p/sh "git" "add" "--" f)]
      (when-not (zero? exit)
        (println "❌ Failed to re-stage" f "after autofix:" err)
        (System/exit exit)))))

(def ^{:stratum 0} ^:private budget-mode-env
  "Env var toggling how a remaining stratum-lint finding after autofix
   (in practice always SL003 — over the layer budget, needs a namespace
   split, since --fix resolves everything else) is treated: unset or any
   value other than \"warn\" blocks the commit; \"warn\" prints and
   continues. Blocking is the default — every rule 210 violation gets
   real enforcement, not a decorative print — with an explicit opt-out
   for a team mid-way through clearing a backlog of pre-existing
   over-budget files."
  "MINIFORGE_STRATUM_BUDGET_MODE")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} lint-only-and-fail!
  "Plain (non-fix) lint over `files`; fails the commit on any finding.
   Used for files with unstaged changes beyond what's staged, where
   autofixing would risk silently staging work-in-progress the developer
   left out on purpose."
  [files]
  (println "⚠️  Skipping autofix for partially-staged file(s) — checking only:"
           (str/join ", " files))
  (let [{:keys [exit out err]} (apply p/sh {:out :string :err :string}
                                      "bb" "-Sdeps" stratum-lint-deps
                                      "-m" "stratum-lint.interface"
                                      files)]
    (when-not (str/blank? out) (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (when-not (zero? exit)
      (println "❌ Stratified-design lint failed — stage the file fully to"
               "allow autofix, or fix headings by hand. Exit code:" exit)
      (System/exit exit))))

(defn ^{:stratum 1} post-fix-lint!
  "Plain (non-fix) lint pass over already-fixed `files`; any remaining
   finding (in practice always SL003 — over the layer budget, needs a
   namespace split — since --fix resolves everything else) fails the
   commit by default, same as any other rule 210 violation — set
   `MINIFORGE_STRATUM_BUDGET_MODE=warn` to print and continue instead,
   e.g. while working through a backlog of pre-existing over-budget
   files. A genuinely unexpected exit (neither clean nor
   findings-present) always fails the commit regardless of mode — a
   broken tool invocation should never pass silently."
  [files]
  (let [{:keys [exit out err]} (apply p/sh {:out :string :err :string}
                                      "bb" "-Sdeps" stratum-lint-deps
                                      "-m" "stratum-lint.interface"
                                      files)
        warn? (= "warn" (System/getenv budget-mode-env))]
    (when-not (str/blank? out)
      (println (if warn? "⚠️" "❌")
               "Stratified-design findings remain after autofix (often a namespace split needed for an over-budget file):")
      (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (cond
      (not (contains? #{0 1} exit))
      (do
        (println "❌ Post-fix lint pass could not run — exit code:" exit)
        (System/exit exit))

      (and (= 1 exit) (not warn?))
      (System/exit exit))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} autofix-and-restage!
  "Run --fix over fully-staged `files`, re-stage on success. Exit
   contract in --fix mode: 0 covers \"already clean\" and \"successfully
   fixed\"; 1 is reserved for what --fix genuinely cannot resolve on its
   own (a parse failure, or a same-file reference cycle) and still fails
   the commit for a human to look at."
  [files]
  (let [{:keys [exit out err]} (apply p/sh {:out :string :err :string}
                                      "bb" "-Sdeps" stratum-lint-deps
                                      "-m" "stratum-lint.interface" "--fix"
                                      files)]
    (when-not (str/blank? out) (println out))
    (when-not (str/blank? err) (binding [*out* *err*] (println err)))
    (if-not (zero? exit)
      (do
        (println "❌ Stratified-design lint could not auto-fix — exit code:" exit)
        (System/exit exit))
      (do
        (restage! files)
        (post-fix-lint! files)))))
