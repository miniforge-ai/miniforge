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
;; Runs inside a bench sandbox. Sequential use only. Reads the codex
;; directory from MINIFORGE_CODEX_PATH; there is no default.
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

(def ^{:stratum 0} refused-exit
  "Exit code for a run that never started, distinct from 0 and 1 so a
   refusal cannot be read as a run that passed or failed."
  2)

(def ^{:stratum 0} baseline-arm "baseline")

(def ^{:stratum 0} treated-arm
  "The arm defined by having a codex; `run-env` keys off this alone."
  "treated")

(def ^{:stratum 0} codex-path-env "MINIFORGE_CODEX_PATH")

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
  "A run's verdict is the strongest across everything it produced:
   reaching the trap site beats not reaching it, and :caught ties
   :sprung because both observe the site."
  {:sprung 2 :caught 2 :not-reached 1 nil 0})

(defn ^{:stratum 0} anomaly
  "Anomaly-shaped failure value (std 005 §Anomaly shape). Local because
   a bb script's classpath carries bb-utils, not the anomaly component."
  [type message data]
  {:anomaly/type type
   :anomaly/message message
   :anomaly/data data
   :anomaly/at (java.time.Instant/now)})

(defn ^{:stratum 0} anomaly?
  [x]
  (boolean (and (map? x) (contains? x :anomaly/type))))

(defn ^{:stratum 0} report-refusal!
  "Print a refusal to stderr. Called only from the CLI boundary below."
  [refusal]
  (binding [*out* *err*]
    (println "REFUSED:" (:anomaly/message refusal))
    (println "        " (pr-str (:anomaly/data refusal)))))

(defn ^{:stratum 0} ancestor
  "The `n`th parent directory of `path`, canonicalized."
  [path n]
  (str (nth (iterate fs/parent (fs/canonicalize path)) n)))

(defn ^{:stratum 0} list-dirs
  [d]
  (if (fs/exists? d) (set (map str (fs/list-dir d))) #{}))

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
  "Verdict map from the frozen detector for `trap` over `dir`, or nil."
  [detector repo trap dir]
  (-> (p/shell {:dir repo :out :string :continue true}
               "bb" detector trap (str dir))
      :out str/trim (some->> (edn/read-string))))

;------------------------------------------------------------------------------ Layer 1

;; Composes Layer 0.

(defn ^{:stratum 1} sandbox-paths
  "Where this script sits inside the sandbox's working clone."
  [script-file]
  (let [eval-dir (ancestor script-file 1)]
    {:eval-dir eval-dir
     :repo (ancestor script-file 3)
     :detector (str (fs/path eval-dir "detect.bb"))
     :specs (str (fs/path eval-dir "specs"))
     :runs (str (fs/path eval-dir "runs.edn"))}))

(defn ^{:stratum 1} codex-path
  "Codex directory the treated arm consults, or nil when unset."
  []
  (some-> (System/getenv codex-path-env) str/trim not-empty))

(defn ^{:stratum 1} reset-anomaly
  "Return the sandbox to its pinned tracked state, or an anomaly. A tree
   still carrying the last run's edits makes this run's verdict
   unattributable."
  [repo]
  (let [{:keys [exit err]} (p/sh {:dir repo :continue true} "git" "reset" "--hard" "-q")]
    (when-not (zero? exit)
      (anomaly :fault "could not reset the sandbox to its pinned state"
               {:repo repo :exit exit :err (str/trim (str err))}))))

(defn ^{:stratum 1} arm-inputs
  "Per-arm run state. MINIFORGE_HOME partitions checkpoints and events;
   task worktrees pool under ~/.miniforge/worktrees regardless of it
   (verified 2026-08-06), so those are attributed by before/after diff."
  [arm]
  (let [home-dir (System/getProperty "user.home")
        arm-home (str (fs/path home-dir ".miniforge" "bench" "home" arm))]
    {:home arm-home
     :events (str (fs/path arm-home "events" "live"))
     :worktrees (str (fs/path home-dir ".miniforge" "worktrees"))}))

(defn ^{:stratum 1} run-env
  "Pinned models, the arm's private MINIFORGE_HOME, and the codex
   present for exactly one arm."
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
  "Materialize a task branch in a temp worktree, run the detector, clean
   up. nil when the branch cannot be materialized — one unreadable branch
   must not lose the whole run's record."
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
  "What already exists before a run, so the run's own output can be
   attributed by difference afterwards."
  [repo {:keys [events worktrees]}]
  {:events (list-dirs events)
   :worktrees (list-dirs worktrees)
   :branches (task-branches repo)})

(defn ^{:stratum 2} run-dogfood!
  "Reset the sandbox, re-copy the spec master, run `bb dogfood` over it.
   Returns `{:exit .. :started .. :ended ..}`, or an anomaly when the run
   could not be started cleanly."
  [{:keys [repo specs]} {:keys [arm trap codex home]}]
  (let [spec-name (trap->spec trap)
        started (str (java.time.Instant/now))]
    (if-let [failure (reset-anomaly repo)]
      failure
      (do
        (fs/copy (fs/path specs spec-name) (fs/path repo "work" spec-name)
                 {:replace-existing true})
        {:exit (:exit (p/shell {:dir repo :env (run-env arm codex home) :continue true}
                               "bb" "dogfood" (str "work/" spec-name)))
         :started started
         :ended (str (java.time.Instant/now))}))))

(defn ^{:stratum 2} run-verdict
  "Strongest detector verdict across every worktree and task branch the
   run produced, with the evidence that earned it."
  [{:keys [repo detector] :as paths} trap worktrees branches]
  (let [verdicts (into [] (concat (keep #(detect detector repo trap %) worktrees)
                                  (keep #(detect-branch paths trap %) branches)))
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
  (when-not (and (#{baseline-arm treated-arm} arm) (trap->spec trap) rep)
    (println usage)
    (System/exit refused-exit))
  (let [inputs (arm-inputs arm)
        before (snapshot (:repo paths) inputs)
        run (run-dogfood! paths (assoc inputs :arm arm :trap trap :codex codex))
        _ (when (anomaly? run)
            (report-refusal! run)
            (System/exit refused-exit))
        after (snapshot (:repo paths) inputs)
        new-wts (vec (remove (:worktrees before) (:worktrees after)))
        new-brs (vec (remove (:branches before) (:branches after)))
        record (merge {:arm arm :trap trap :rep rep
                       :started (:started run) :ended (:ended run) :exit (:exit run)
                       :workflow-ids (mapv #(str/replace (fs/file-name %) #"\.edn$" "")
                                           (remove (:events before) (:events after)))
                       :worktrees new-wts
                       :task-branches new-brs}
                      (run-verdict paths trap new-wts new-brs))]
    (spit (:runs paths) (str (pr-str record) "\n") :append true)
    (println "TRAP-RUN-RECORDED" (pr-str record))
    (System/exit (:exit run))))
