#!/usr/bin/env bb
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
;; Seeded-trap bench — one run of one arm. RUNSHEET.md is normative.
;;
;; Usage: bb eval/codex-traps/run-trap.bb <baseline|treated> <trap-a|trap-b|trap-c> <rep>
;;
;; Runs only inside a bench sandbox provisioned by `bb bench:provision`;
;; see `isolation-anomaly`. Sequential use only. Reads MINIFORGE_CODEX_PATH
;; (required by the treated arm, no default) and MINIFORGE_BENCH_SOURCE
;; (optional; the checkout the sandbox was cloned from).
;;
;; No `ns` form: the runsheet names this file `run-trap.bb`, and clj-kondo
;; requires a namespace to match its file name character for character.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

;------------------------------------------------------------------------------ Layer 0

;; No in-namespace dependencies.

(def ^{:stratum 0} usage
  "usage: bb eval/codex-traps/run-trap.bb <baseline|treated> <trap-a|trap-b|trap-c> <rep>")

(defn ^{:stratum 0} valid-rep?
  "One filename segment: letters, digits, dot, underscore, dash."
  [rep]
  (boolean (re-matches #"[A-Za-z0-9._-]+" (str rep))))

(def ^{:stratum 0} refused-exit
  "Exit code for a run that never started, distinct from 0 and 1 so a
   refusal cannot be read as a run that passed or failed."
  2)

(def ^{:stratum 0} baseline-arm "baseline")

(def ^{:stratum 0} treated-arm
  "The arm defined by having a codex; `run-env` keys off this alone."
  "treated")

(def ^{:stratum 0} codex-path-env "MINIFORGE_CODEX_PATH")

(def ^{:stratum 0} bench-source-env
  "Launching checkout, optional. Set, `bb bench:verify` also compares
   git common dirs; unset, it judges the sandbox alone."
  "MINIFORGE_BENCH_SOURCE")

(def ^{:stratum 0} mirror-dir-name
  "Throwaway bare mirror the sandbox's origin must point at. Must match
   `bench/mirror-dir-name`, which is what `bb bench:provision` creates."
  "origin.git")

(def ^{:stratum 0} trap->spec
  {"trap-a" "trap-a-ledger-key-rename.spec.edn"
   "trap-b" "trap-b-pr-size-log-artifact.spec.edn"
   "trap-c" "trap-c-ensure-fleet-config.spec.edn"})

(def ^{:stratum 0} pinned-env
  "Models pinned by the runsheet, each exported explicitly because
   `--backend` alone does not pin one."
  {"MINIFORGE_LLM_BACKEND" "claude"
   "MINIFORGE_LLM_MODEL" "claude-sonnet-4-6"
   "MINIFORGE_AGENT_THINKING_MODEL" "claude-opus-4-6"
   "MINIFORGE_AGENT_EXECUTION_MODEL" "claude-sonnet-4-6"})

(def ^{:stratum 0} verdict-rank
  "A run's verdict is the strongest thing it produced. :sprung outranks
   :caught, so mixed evidence records the sprung trap — conservative for
   a catch rate. :detector-error outranks nothing, showing only when no
   real verdict exists. Full detail stays in :all-verdicts."
  {:sprung 3 :caught 2 :not-reached 1 :detector-error 0 nil 0})

(defn ^{:stratum 0} anomaly
  "Anomaly-shaped failure value (std 005 §Anomaly shape). Local because
   a bb script's classpath carries bb-utils, not the anomaly component."
  [type message data]
  {:anomaly/type type
   :anomaly/message message
   :anomaly/data data
   :anomaly/at (java.time.Instant/now)})

(defn ^{:stratum 0} report-refusal!
  "Print a refusal to stderr. Called only from the CLI boundary below."
  [refusal]
  (binding [*out* *err*]
    (println "REFUSED:" (:anomaly/message refusal))
    (println "        " (pr-str (:anomaly/data refusal)))))

(defn ^{:stratum 0} ancestor
  "The `n`th parent directory of `path`, canonicalized, or the
   filesystem root when `path` is shallower than that. Total, so a
   harness copied somewhere unexpected refuses rather than crashing."
  [path n]
  (->> (iterate fs/parent (fs/canonicalize path))
       (take-while some?) (take (inc n)) last str))

(defn ^{:stratum 0} list-dirs
  [d]
  (if (fs/exists? d) (set (map str (fs/list-dir d))) #{}))

(defn ^{:stratum 0} local-dir
  "Canonical path of `path` when it names a directory, resolved against
   `dir` when relative, else nil. Git resolves a relative remote against
   the repo, not the process's cwd, so a raw comparison judges the wrong
   directory."
  [dir path]
  (try
    (let [f (java.io.File. (str path))
          f (if (.isAbsolute f) f (java.io.File. (str dir) (str path)))]
      (when (.isDirectory f) (.getCanonicalPath f)))
    (catch Exception _ nil)))

(defn ^{:stratum 0} git-out
  "Trimmed stdout of a git command run in `dir`, or nil when it failed,
   said nothing, or could not be launched at all."
  [dir & args]
  ;; Plain try, not slingshot: bb ships none and the catch is class-only
  ;; (std 211 exemption a).
  (try
    (let [{:keys [exit out]} (apply p/sh {:dir dir :out :string :err :string
                                          :continue true}
                                    "git" args)]
      (when (zero? exit) (not-empty (str/trim (str out)))))
    (catch Exception _ nil)))

(defn ^{:stratum 0} detect
  "Verdict map from the frozen detector for `trap` over `dir`. Total: a
   detector that exits non-zero, says nothing, or prints garbage yields
   :detector-error rather than discarding an hours-old run's record."
  [detector repo trap dir]
  (let [{:keys [exit out]} (p/shell {:dir repo :out :string :err :string
                                     :continue true}
                                    "bb" detector trap (str dir))
        text (str/trim (str out))
        parsed (when (and (zero? exit) (seq text))
                 (try (edn/read-string text) (catch Exception _ nil)))]
    (or parsed
        {:verdict :detector-error
         :evidence [(str "detector exit " exit ", output: " (pr-str text))]})))

;------------------------------------------------------------------------------ Layer 1

;; Composes Layer 0.

(defn ^{:stratum 1} sandbox-paths
  "Layout `bb bench:provision` creates: this script sits at
   `<root>/repo/eval/codex-traps/run-trap.bb`, beside `<root>/origin.git`."
  [script-file]
  (let [eval-dir (ancestor script-file 1)]
    {:repo (ancestor script-file 3)
     :root (ancestor script-file 4)
     :mirror (str (fs/path (ancestor script-file 4) mirror-dir-name))
     :detector (str (fs/path eval-dir "detect.bb"))
     :specs (str (fs/path eval-dir "specs"))
     :runs (str (fs/path eval-dir "runs.edn"))}))

(defn ^{:stratum 1} isolation-anomaly
  "nil when this sandbox is safe to run a bench in; an anomaly otherwise.
   `bb bench:verify` rejects one sharing a git common dir with another
   checkout; the mirror check rejects one whose origin could still reach
   a real remote — invisible to bench:verify, and passed by a tracked
   harness run from an ordinary checkout. Undeterminable means refuse."
  [{:keys [repo mirror]}]
  ;; bb runs from the launching checkout when MINIFORGE_BENCH_SOURCE names
  ;; one, so a sandbox pinned before `bench:verify` existed is still
  ;; gated; the dirs judged are arguments, not the working directory.
  (let [source (some-> (System/getenv bench-source-env) str/trim not-empty)
        verify (try
                 (apply p/shell {:dir (or source repo) :continue true
                                 :out :string :err :string}
                        (concat ["bb" "bench:verify" repo] (when source [source])))
                 (catch Exception e {:exit refused-exit :err (.getMessage e)}))
        origin (git-out repo "remote" "get-url" "origin")
        origin-dir (some->> origin (local-dir repo))]
    (cond
      (not (fs/exists? (fs/path repo "bb.edn")))
      (anomaly :invalid-input "no bb.edn — path resolution is wrong" {:repo repo})

      (not (zero? (:exit verify)))
      (anomaly :fault "bb bench:verify rejected this sandbox"
               {:repo repo :exit (:exit verify)
                :out (str/trim (str (:out verify)))
                :err (str/trim (str (:err verify)))})

      (nil? origin)
      (anomaly :fault "sandbox has no readable origin remote" {:repo repo})

      (not (fs/directory? mirror))
      (anomaly :fault "no mirror beside the sandbox" {:expected mirror})

      (not= origin-dir (local-dir repo mirror))
      (anomaly :fault "origin is not the sandbox's mirror — a run could push for real"
               {:origin origin :resolved origin-dir :expected mirror})

      (not= "true" (git-out origin-dir "rev-parse" "--is-bare-repository"))
      (anomaly :fault "sandbox origin mirror is not a bare repository" {:origin origin-dir})

      :else nil)))

(defn ^{:stratum 1} codex-path
  []
  (some-> (System/getenv codex-path-env) str/trim not-empty))

(defn ^{:stratum 1} codex-path-anomaly
  "nil when `arm` has the codex it needs; an anomaly otherwise. No
   default: an absent codex would run a second baseline under a treated
   label and silently halve the matrix."
  [arm path]
  (cond
    (not= arm treated-arm) nil

    (nil? path)
    (anomaly :invalid-input (str "the treated arm needs " codex-path-env " set")
             {:arm arm :env codex-path-env})

    (not (fs/directory? path))
    (anomaly :invalid-input (str codex-path-env " is not a directory")
             {:arm arm :env codex-path-env :path path})

    :else nil))

(defn ^{:stratum 1} spec-anomaly
  "`copy-anomaly`'s condition, checked before anything is touched."
  [{:keys [specs]} trap]
  (let [master (fs/path specs (trap->spec trap))]
    (when-not (fs/exists? master)
      (anomaly :not-found "spec master missing from the harness" {:master (str master)}))))

(defn ^{:stratum 1} copy-anomaly
  "The runner moves a failed spec into work/failed/ and `git reset`
   cannot restore an untracked copy, so every run re-copies the master."
  [specs repo spec-name]
  (try
    (fs/copy (fs/path specs spec-name) (fs/path repo "work" spec-name)
             {:replace-existing true})
    nil
    (catch Exception e
      (anomaly :fault "could not stage the spec master"
               {:spec spec-name :err (.getMessage e)}))))

(defn ^{:stratum 1} reset-anomaly
  "A tree still carrying the last run's edits makes this run's verdict
   unattributable."
  [repo]
  (let [{:keys [exit err]} (p/sh {:dir repo :continue true} "git" "reset" "--hard" "-q")]
    (when-not (zero? exit)
      (anomaly :fault "could not reset the sandbox to its pinned state"
               {:repo repo :exit exit :err (str/trim (str err))}))))

(defn ^{:stratum 1} arm-inputs
  "Per-arm run state under the sandbox's own root, so two sandboxes
   cannot share an event stream; for the default sandbox this is the
   pre-registered ~/.miniforge/bench/home/<arm>. Task worktrees pool
   under ~/.miniforge/worktrees regardless of MINIFORGE_HOME (verified
   2026-08-06)."
  [root arm]
  (let [arm-home (str (fs/path root "home" arm))]
    {:home arm-home
     :events (str (fs/path arm-home "events" "live"))
     :worktrees (str (fs/path (System/getProperty "user.home")
                              ".miniforge" "worktrees"))}))

(defn ^{:stratum 1} run-env
  "Pinned models, the arm's MINIFORGE_HOME, and the codex present for
   exactly one arm."
  [arm codex home]
  (cond-> (merge {} (System/getenv) pinned-env {"MINIFORGE_HOME" home})
    (= arm treated-arm) (assoc codex-path-env codex)
    (not= arm treated-arm) (dissoc codex-path-env)))

(defn ^{:stratum 1} task-branches
  "Local task-* branches in the sandbox. Task worktrees are cleaned up
   after a run, but the task branch survives — it is the durable record
   of the run's diff (verified on the trap-b shakeout)."
  [repo]
  (->> (git-out repo "for-each-ref" "--format=%(refname:short)" "refs/heads/task-*")
       str str/split-lines (remove str/blank?) set))

(defn ^{:stratum 1} detect-branch
  "nil when the branch cannot be materialized — one unreadable branch
   must not cost the whole run's record."
  [{:keys [repo detector]} trap branch]
  (let [tmp (str (fs/path (fs/temp-dir) (str "trap-detect-" branch)))]
    (try
      (when (zero? (:exit (p/sh {:dir repo :continue true}
                                "git" "worktree" "add" "-q" "--detach" tmp branch)))
        (detect detector repo trap tmp))
      (finally
        (p/sh {:dir repo :continue true} "git" "worktree" "remove" "--force" tmp)))))

;------------------------------------------------------------------------------ Layer 2

;; Composes Layer 1.

(defn ^{:stratum 2} snapshot
  "What exists before a run, so its own output can be attributed by
   difference afterwards."
  [repo {:keys [events worktrees]}]
  {:events (list-dirs events)
   :worktrees (list-dirs worktrees)
   :branches (task-branches repo)})

(defn ^{:stratum 2} run-dogfood!
  "Returns `{:exit .. :started .. :ended ..}`, or an anomaly when the run
   could not be started from a clean tree."
  [{:keys [repo specs runs]} {:keys [arm trap codex home rep]}]
  (let [spec-name (trap->spec trap)
        started (str (java.time.Instant/now))
        ;; Full dogfood stdout+stderr per run: the logger lines (e.g.
        ;; :implementer/prompt-sections) live ONLY there — stream dumps
        ;; are the model's output, events are a subset — and tail -1 was
        ;; discarding the ground truth the third series was run to get.
        log-dir (str (fs/path (fs/parent runs) "logs"))
        _ (fs/create-dirs log-dir)
        log-file (fs/file (str (fs/path log-dir (str arm "-" trap "-" rep ".log"))))]
    (if-let [failure (or (reset-anomaly repo) (copy-anomaly specs repo spec-name))]
      failure
      ;; stderr is merged into stdout at the process level (`:err :out`),
      ;; so the log is one faithful stream, not two writers on one file.
      {:exit (:exit (p/shell {:dir repo :env (run-env arm codex home) :continue true
                              :out log-file :err :out}
                             "bb" "dogfood" (str "work/" spec-name)))
       :log (str log-file)
       :started started
       :ended (str (java.time.Instant/now))})))

(defn ^{:stratum 1} snapshot-commits
  "Latest in-window stash snapshot per task, as commit SHAs. The runner
   deletes a task's branch when its sub-workflow completes CLEANLY, so
   the runs that did the work leave no branch — the 2026-08-11 salvage
   found 7 of 8 :not-reached verdicts were exactly this. The runner's
   periodic 'WIP on task-*' stash commits survive as unreachable
   objects; the latest one inside [started, ended] per task is the most
   complete tree the run left behind. git fsck costs ~a minute — noise
   against a multi-hour run, and the price of a verdict that reads the
   record instead of the survivors."
  [repo started ended]
  (let [t (fn [s] (java.time.Instant/parse s))
        [s e] [(t started) (t ended)]
        shas (->> (str (git-out repo "fsck" "--unreachable" "--no-reflogs"))
                  str/split-lines
                  (keep #(second (re-find #"unreachable commit ([0-9a-f]{40})" %))))
        ;; One batched git log over every unreachable commit — a per-sha
        ;; `git show` loop costs minutes at a few thousand objects.
        described (when (seq shas)
                    (try
                      (:out (p/sh {:dir repo :in (str/join "\n" shas)
                                   :out :string :err :string :continue true}
                                  "git" "log" "--no-walk=unsorted"
                                  "--format=%cI\t%H\t%s" "--stdin"))
                      (catch Exception _ nil)))
        wips (keep (fn [line]
                     (let [[date sha subject] (str/split line #"\t" 3)
                           task (some->> subject
                                         (re-find #"^WIP on (task-[0-9a-f]+):")
                                         second)]
                       ;; Malformed dates refuse the snapshot, not the run.
                       (try
                         (when (and date sha task
                                    (not (.isBefore (t date) s))
                                    (not (.isAfter (t date) e)))
                           {:task task :at (t date) :sha sha})
                         (catch Exception _ nil))))
                   (str/split-lines (str described)))]
    (->> (group-by :task wips)
         vals
         (map #(:sha (apply max-key (fn [w] (.toEpochMilli ^java.time.Instant (:at w))) %)))
         sort
         vec)))

(defn ^{:stratum 2} run-verdict
  "Strongest verdict across everything the run produced — surviving
   worktrees, surviving branches, and the run-window stash snapshots —
   with the evidence that earned it."
  [{:keys [repo detector] :as paths} trap worktrees branches snapshots]
  (let [verdicts (into [] (concat (keep #(detect detector repo trap %) worktrees)
                                  (keep #(detect-branch paths trap %) branches)
                                  (keep #(detect-branch paths trap %) snapshots)))
        best (when (seq verdicts)
               (apply max-key #(get verdict-rank (:verdict %) 0) verdicts))]
    {:verdict (get best :verdict :no-worktree)
     :evidence (:evidence best)
     :all-verdicts (mapv :verdict verdicts)}))

;; Absolute CLI boundary (std 005) — the only place that exits, and
;; (with report-refusal!) the only place that prints.
(let [[arm trap rep] *command-line-args*
      paths (sandbox-paths *file*)
      codex (codex-path)]
  ;; rep keys the runs.edn row and names the log file verbatim, so it is
  ;; one filename segment or nothing -- refused, never rewritten.
  (when-not (and (#{baseline-arm treated-arm} arm) (trap->spec trap) (valid-rep? rep))
    (println usage)
    (System/exit refused-exit))
  (when-let [refusal (or (isolation-anomaly paths)
                         (codex-path-anomaly arm codex)
                         (spec-anomaly paths trap))]
    (report-refusal! refusal)
    (System/exit refused-exit))
  (let [inputs (arm-inputs (:root paths) arm)
        before (snapshot (:repo paths) inputs)
        run (run-dogfood! paths (assoc inputs :arm arm :trap trap :codex codex :rep rep))
        _ (when (:anomaly/type run)
            (report-refusal! run)
            (System/exit refused-exit))
        after (snapshot (:repo paths) inputs)
        ;; Sorted: runs.edn is append-only, so a row has to diff cleanly
        ;; against the next one rather than vary with set iteration order.
        new-wts (vec (sort (remove (:worktrees before) (:worktrees after))))
        new-brs (vec (sort (remove (:branches before) (:branches after))))
        snapshots (snapshot-commits (:repo paths) (:started run) (:ended run))
        record (merge {:arm arm :trap trap :rep rep
                       :started (:started run) :ended (:ended run) :exit (:exit run)
                       :workflow-ids (mapv #(str/replace (fs/file-name %) #"\.edn$" "")
                                           (sort (remove (:events before) (:events after))))
                       :worktrees new-wts
                       :task-branches new-brs
                       :snapshots snapshots}
                      (run-verdict paths trap new-wts new-brs snapshots))]
    ;; stdout first: the run has already been paid for, so a full disk
    ;; must not take its only record with it.
    (println "TRAP-RUN-RECORDED" (pr-str record))
    (try (spit (:runs paths) (str (pr-str record) "\n") :append true)
         (catch Exception e
           (binding [*out* *err*]
             (println "runs.edn append failed, stdout row is the only copy:"
                      (.getMessage e)))))
    (System/exit (:exit run))))
