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
(ns bench
  "Whether the bench/dogfood sandbox is actually isolated from the
   checkout it was launched from.

   A bench points `origin` at a throwaway local mirror so a completed run
   cannot open a real PR. A linked `git worktree` is not a safe place to
   do that: it owns HEAD, the index, and the working tree, while
   `.git/config` and every other ref live in the shared common dir — so
   `remote set-url` and `fetch` inside it rewrite the *launching*
   checkout. See DOGFOODING.md §Bench Runs for the 2026-08-06 incident
   that shape produced.

   Per std 005 only the Layer 2 bb-task entry point prints and exits."
  (:require
   [bench-git :as git]
   [clojure.string :as str])
  (:import
   [java.io File]))

;------------------------------------------------------------------------------ Layer 0

;; No in-namespace dependencies.
(def ^{:stratum 0} repo-dir-name
  "Working clone the bench runs in, under the bench root."
  "repo")

(def ^{:stratum 0} default-bench-root
  "Bench sandbox root. Sits beside the other miniforge run state so a
   bench survives reboots for post-mortem — the same rationale as the
   worktree executor's `~/.miniforge/worktrees` default."
  (or (System/getenv "MINIFORGE_BENCH_ROOT")
      (str (System/getProperty "user.home") "/.miniforge/bench")))

;; Composes the Layer 0 constants and the `bench-git` plumbing.
(defn ^{:stratum 0} isolation-report
  "Whether `bench-dir` can safely own its own remotes and refs.

   `:isolated?` is false when `bench-dir` is a linked worktree, or when it
   shares a git common dir with `source-dir` — in either case a
   `remote set-url` or `fetch` inside it mutates the other checkout.
   `source-dir` may be nil to check `bench-dir` on its own."
  [bench-dir source-dir]
  (let [bench (git/git-dirs bench-dir)
        source (when source-dir (git/git-dirs source-dir))
        linked? (boolean (and bench (not= (:git-dir bench) (:common-dir bench))))
        shared? (boolean (and bench source (= (:common-dir bench) (:common-dir source))))]
    {:bench-dir (str bench-dir)
     :source-dir (some-> source-dir str)
     :git-dirs bench
     :linked-worktree? linked?
     :shares-source-git-dir? shared?
     :isolated? (boolean (and bench (not linked?) (not shared?)))}))

;------------------------------------------------------------------------------ Layer 1

;; Composes Layer 1. Absolute CLI boundary (std 005) — the only layer that
;; prints and exits.
(defn ^{:stratum 1} verify
  "Fail-closed guard for a bench runner. Exits non-zero unless the bench
   repo owns its own config and refs.
   Usage: bb bench:verify [bench-repo-dir] [source-dir]"
  [& args]
  (let [[bench-dir source-dir] (map #(some-> % str/trim not-empty) args)
        bench-dir (or bench-dir (str (File. default-bench-root repo-dir-name)))
        report (isolation-report bench-dir source-dir)]
    (println "bench-repo:" (:bench-dir report))
    (println "  git-dir:" (get-in report [:git-dirs :git-dir]))
    (println "  common-dir:" (get-in report [:git-dirs :common-dir]))
    (println "  origin:" (git/remote-url bench-dir "origin"))
    (if (:isolated? report)
      (println "✅ isolated — remotes and refs are its own")
      (do
        (when-not (:git-dirs report)
          (println "❌ not a git checkout"))
        (when (:linked-worktree? report)
          (println "❌ linked worktree — set-url/fetch here rewrites the parent checkout"))
        (when (:shares-source-git-dir? report)
          (println "❌ shares a git common dir with" (:source-dir report)))
        (println "   provision the bench as a clone of the launching checkout instead")
        (System/exit 1)))))
