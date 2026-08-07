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

   Per std 005 only the bb-task entry points — `verify` and `provision` —
   print and exit; everything below them returns an anomaly as data."
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

(def ^{:stratum 0} mirror-dir-name
  "Bare mirror the bench's origin points at, under the bench root. Exists
   so a completed run's push lands here instead of on GitHub."
  "origin.git")

(def ^{:stratum 0} default-bench-root
  "Bench sandbox root. Sits beside the other miniforge run state so a
   bench survives reboots for post-mortem — the same rationale as the
   worktree executor's `~/.miniforge/worktrees` default."
  (or (System/getenv "MINIFORGE_BENCH_ROOT")
      (str (System/getProperty "user.home") "/.miniforge/bench")))

(defn ^{:stratum 0} anomaly
  "Anomaly-shaped failure value (std 005 §Anomaly shape). Local because
   the bb task classpath carries `bb-utils` only, not the
   `ai.miniforge.anomaly` component."
  [type message data]
  {:anomaly/type type
   :anomaly/message message
   :anomaly/data data
   :anomaly/at (java.time.Instant/now)})

(defn ^{:stratum 0} anomaly?
  [x]
  (boolean (and (map? x) (contains? x :anomaly/type))))

(defn ^{:stratum 0} qualified-branch-ref
  "Normalize `branch` to exactly one `refs/heads/` prefix, or nil when
   nothing but prefixes remain.

   Git DWIMs an unqualified push destination, so a destination already
   carrying a partial `heads/...` prefix expands to `refs/heads/heads/...`
   — which then makes `git ls-remote origin main` resolve ambiguously
   against the real `refs/heads/main`. Every refspec this namespace builds
   goes through here. Only leading `refs/` and `heads/` segments are
   stripped; `feature/heads-up` keeps its interior intact."
  [branch]
  (let [stripped (loop [s (str/trim (str branch))]
                   (cond
                     (str/starts-with? s "refs/") (recur (subs s (count "refs/")))
                     (str/starts-with? s "heads/") (recur (subs s (count "heads/")))
                     :else s))]
    (when-not (str/blank? stripped)
      (str "refs/heads/" stripped))))

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

;; Composes Layer 0.
(defn ^{:stratum 1} provision!
  "Provision a bench sandbox under `:root` from the checkout at `:source`.

   Clones `:source` to `<root>/repo` at `:ref`, creates the throwaway bare
   mirror `<root>/origin.git`, pushes the pinned commit to it as a fully
   qualified `refs/heads/<branch>`, and points only the clone's origin at
   the mirror. Seeding by explicit push rather than by mirroring every ref
   also stops junk refs in the source propagating into the bench.

   The launching checkout is read from and never written to: its
   `remote.origin.url` is captured before the work and re-checked after,
   and a mismatch fails the provision.

   Refuses outright when `<root>/repo` exists — a bench in flight keeps
   its worktrees and task branches there, so removing a previous bench is
   the operator's call, not a flag on this function.

   Returns the provisioning report, or an anomaly."
  [{:keys [source root ref branch]
    :or {root default-bench-root ref "HEAD" branch "main"}}]
  (let [source (git/absolute-path "." source)
        repo-dir (str (File. (str root) repo-dir-name))
        mirror-dir (str (File. (str root) mirror-dir-name))
        origin-before (git/remote-url source "origin")
        target-ref (qualified-branch-ref branch)]
    (cond
      (nil? target-ref)
      (anomaly :invalid-input "branch name is empty once ref prefixes are stripped"
               {:branch branch})

      (some #(.exists (File. ^String %)) [repo-dir mirror-dir])
      (anomaly :conflict "bench root already populated; remove it to re-provision"
               {:repo-dir repo-dir :mirror-dir mirror-dir})

      (nil? (git/git-dirs source))
      (anomaly :invalid-input "bench source is not a git checkout" {:source source})

      :else
      (do
        (.mkdirs (File. (str root)))
        ;; Thunks, not results: a vector of `(git ...)` calls would run every
        ;; step before anything inspected an exit code, so a failed clone
        ;; would still init a mirror and leave partial state behind.
        (let [steps [[:clone #(git/git "." "clone" "--quiet" "--no-checkout" source repo-dir)]
                     [:checkout #(git/git repo-dir "checkout" "--quiet" "--detach" ref)]
                     [:init-mirror #(git/git "." "init" "--quiet" "--bare" mirror-dir)]
                     [:seed-mirror #(git/git repo-dir "push" "--quiet" mirror-dir
                                             (str "HEAD:" target-ref))]
                     [:mirror-head #(git/git mirror-dir "symbolic-ref" "HEAD" target-ref)]
                     [:redirect-origin #(git/git repo-dir "remote" "set-url" "origin" mirror-dir)]]
              failed (reduce (fn [_ [step run-step!]]
                               (let [r (run-step!)]
                                 (when-not (zero? (:exit r))
                                   (reduced [step r]))))
                             nil
                             steps)
              origin-after (git/remote-url source "origin")
              report (isolation-report repo-dir source)]
          (cond
            failed
            (anomaly :fault (str "bench provisioning failed at " (name (first failed)))
                     {:step (first failed)
                      :exit (:exit (second failed))
                      :err (str/trim (str (:err (second failed))))})

            (not= origin-before origin-after)
            (anomaly :fault "bench provisioning mutated the launching checkout's origin"
                     {:before origin-before :after origin-after})

            (not (:isolated? report))
            (anomaly :fault "bench repo is not isolated from the launching checkout" report)

            :else
            (assoc report
                   :repo-dir repo-dir
                   :mirror-dir mirror-dir
                   :target-ref target-ref
                   :source-origin origin-after
                   :bench-origin (git/remote-url repo-dir "origin"))))))))

;; Absolute CLI boundary (std 005) — the only fns that print and exit.
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

;------------------------------------------------------------------------------ Layer 2

;; Composes Layer 1.
(defn ^{:stratum 2} provision
  "Provision an isolated bench sandbox.
   Usage: bb bench:provision [source-dir] [ref] [branch]"
  [& args]
  (let [[source ref branch] (map #(some-> % str/trim not-empty) args)
        report (provision! (cond-> {:source (or source ".")}
                             ref (assoc :ref ref)
                             branch (assoc :branch branch)))]
    (if (anomaly? report)
      (do
        (println "❌" (:anomaly/message report))
        (println "  " (pr-str (:anomaly/data report)))
        (System/exit 1))
      (do
        (println "✅ bench provisioned")
        (println "  repo:" (:repo-dir report))
        (println "  mirror:" (:mirror-dir report))
        (println "  bench origin:" (:bench-origin report))
        (println "  launching checkout origin (unchanged):" (:source-origin report))))))
