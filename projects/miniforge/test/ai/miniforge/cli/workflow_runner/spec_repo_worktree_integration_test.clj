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
(ns ai.miniforge.cli.workflow-runner.spec-repo-worktree-integration-test
  "Integration test (real git and filesystem): a spec in another repository
   than the working directory's gets its task worktree, and its task branch,
   from the spec's repository. The CLI gave the runner no :repo-path, so the
   runner's \".\" default cut the worktree from the repository the process
   ran in, on that checkout's branch: the agent worked in a checkout that
   did not hold the spec's files.

   The run goes through the real CLI context, runner and worktree executor.
   The process's own checkout is never written to: a \".\" repo path, which
   only the bug produces, is pointed at a second throwaway repository that
   stands in for the working directory's. The miniforge home is a temp dir."
  (:require
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.cli.workflow-runner :as sut]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.cli.workflow-runner.execution-mode :as execution-mode]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]
   [ai.miniforge.cli.workflow-runner.preflight :as preflight]
   [ai.miniforge.cli.workflow-runner.provenance :as provenance]
   [ai.miniforge.cli.workflow-runner.sandbox :as sandbox]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.cli.workflow-runner.spec-kanban :as spec-kanban]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} probe-phase :spec-repo-worktree-probe)

(def ^{:stratum 0} probe-budget
  "The smallest budget a phase accepts; the probe phase makes no LLM call."
  {:tokens 1 :iterations 1 :time-seconds 1})

(def ^{:stratum 0} spec-file
  "Path of the spec file inside its repository. A subdirectory, so the
   spec's source dir differs from the repository root it resolves to."
  "work/spec.edn")

(def ^{:stratum 0} task-branch-prefix
  "Prefix of the branch the worktree executor creates for a task worktree
   (`task-<first 8 chars of the task id>`)."
  "task-")

(def ^{:stratum 0} repo-config
  "Local config of each throwaway repository: an identity for its seed
   commit, and signing off."
  [["user.email" "spec-repo-test@example.invalid"]
   ["user.name" "Spec Repo Test"]
   ["commit.gpgsign" "false"]])

(def ^{:stratum 0} observed
  "What the probe phase saw of the worktree it ran in."
  (atom nil))

(defn ^{:stratum 0} git!
  "Trimmed stdout of git run in `dir`, with every GIT_* variable dropped
   from the environment (a hook's GIT_DIR would otherwise send the command
   to the hook's repository). Throws on a non-zero exit."
  [dir & args]
  (let [env (into {} (remove #(str/starts-with? (key %) "GIT_")) (System/getenv))
        {:keys [exit out err]} (apply shell/sh "git" "-C" (str dir) (concat args [:env env]))]
    (when-not (zero? exit)
      (throw (ex-info "test git command failed" {:dir (str dir) :args args :err err})))
    (str/trim out)))

(defn ^{:stratum 0} canonical [path] (str (fs/canonicalize path)))

(defn ^{:stratum 0} redirect-dot
  "`acquire!` with a \".\" or absent repo path (the worktree executor reads
   absent as \".\") pointed at `cwd-repo`."
  [acquire! cwd-repo]
  (fn [executor task-id env-config]
    (let [repo-path (:repo-path env-config)]
      (acquire! executor task-id
                (cond-> env-config
                  (or (nil? repo-path) (= "." (str repo-path))) (assoc :repo-path cwd-repo))))))

(defn ^{:stratum 0} home-config-loader
  "`load-merged-config` reading the user config from `home`."
  [load-config home]
  (fn
    ([] (load-config (str (fs/path home "config.edn"))))
    ([path] (load-config path))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-spec!
  "Run a spec whose source dir is `source-dir` through
   `run-workflow-from-spec!`, with the collaborators that reach an LLM, the
   operator channel or the user's config stubbed, the miniforge home at
   `home`, and a \".\" repo path sent to `cwd-repo`."
  [source-dir cwd-repo home]
  (let [stream (es/create-event-stream {:sinks []})
        workflow {:workflow/id probe-phase
                  :workflow/version "1.0.0"
                  :workflow/pipeline [{:phase probe-phase}]}
        spec {:spec/title "probe"
              :spec/workflow-type probe-phase
              :spec/source-dir source-dir}]
    (with-redefs-fn
      {#'gc-hooks/run-gc-pass-best-effort! (constantly nil)
       #'gc-hooks/enqueue-workflow-gc-best-effort! (constantly nil)
       #'execution-mode/allowed-mode! (constantly :local)
       #'setup/load-or-create-workflow (constantly workflow)
       #'context/create-llm-client (constantly nil)
       #'context/resolve-acting (constantly nil)
       #'context/require-operator? (constantly false)
       #'sandbox/infer-repo-url (constantly nil)
       #'execution/create-artifact-store (constantly nil)
       #'es/create-event-stream (constantly stream)
       #'supervisory/attach! (constantly nil)
       #'correlator/attach! (constantly nil)
       #'display/start-progress! (constantly (constantly nil))
       #'control/register-workflow-control! (constantly nil)
       #'control/release-workflow-control! (constantly nil)
       #'dashboard/print-dashboard-status! (constantly nil)
       #'provenance/print-runtime-provenance! (constantly nil)
       #'preflight/run-backend-preflight! (constantly nil)
       #'spec-kanban/start-spec! (fn [input _provenance] [nil input])
       #'spec-kanban/finish-spec! (constantly nil)
       #'sut/trigger-meta-loop-after-workflow! (constantly nil)
       #'config/miniforge-home (constantly home)
       #'config/load-merged-config (home-config-loader config/load-merged-config home)
       #'config/user-config-requires-governed? (constantly false)
       #'dag/acquire-environment! (redirect-dot dag/acquire-environment! cwd-repo)}
      (fn []
        (with-out-str
          (sut/run-workflow-from-spec! spec {:quiet true}))))))

(defn ^{:stratum 1} init-repo!
  "A repository at `dir` on branch main, one commit holding the file at
   relative path `file`. Returns its canonical path."
  [dir file]
  (let [config-file (str (fs/path dir ".git" "config"))]
    (fs/create-dirs (fs/parent (fs/path dir file)))
    (git! dir "init" "--quiet" "-b" "main")
    (doseq [[k v] repo-config]
      (git! dir "config" "--file" config-file k v))
    (spit (str (fs/path dir file)) "{:spec/title \"probe\"}\n")
    (git! dir "add" file)
    (git! dir "commit" "--quiet" "--no-verify" "-m" "seed")
    (canonical dir)))

(defn ^{:stratum 1} task-branches [repo]
  (remove str/blank? (str/split-lines (git! repo "branch" "--list" (str task-branch-prefix "*")
                                            "--format=%(refname:short)"))))

(defn ^{:stratum 1} record-worktree
  "Complete the probe phase, recording the repository and branch of the
   worktree it ran in and whether that worktree holds the spec's file."
  [ctx]
  (let [worktree (:execution/worktree-path ctx)
        common-dir (canonical (git! worktree "rev-parse" "--path-format=absolute"
                                    "--git-common-dir"))
        branch (git! worktree "rev-parse" "--abbrev-ref" "HEAD")
        spec-present? (fs/exists? (fs/path worktree spec-file))]
    (reset! observed {:common-dir common-dir
                      :branch branch
                      :spec-present? spec-present?})
    (-> ctx
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :result] (phase/success nil "ok" {})))))

;------------------------------------------------------------------------------ Layer 2

(defmethod ^{:stratum 2} phase/get-phase-interceptor-method :spec-repo-worktree-probe
  [config]
  {:name probe-phase
   :config (phase/merge-with-defaults config)
   :enter #(phase/enter-context % probe-phase :workflow-tester [] probe-budget
                                (System/currentTimeMillis) nil)
   :leave record-worktree
   :error (fn [ctx _ex] ctx)})

(deftest ^{:stratum 2} a-spec-in-another-repository-gets-its-worktree-from-it-test
  (fs/with-temp-dir [root {:prefix "mf-spec-repo-"}]
    (let [spec-repo (init-repo! (fs/path root "spec-repo") spec-file)
          cwd-repo (init-repo! (fs/path root "cwd-repo") "seed.edn")
          source-dir (str (fs/parent (fs/path spec-repo spec-file)))
          home (str (fs/create-dirs (fs/path root "home")))
          spec-git-dir (canonical (fs/path spec-repo ".git"))]
      (reset! observed nil)
      (run-spec! source-dir cwd-repo home)
      (is (some? @observed) "the probe phase ran")
      (testing "given a spec in another repository → the agent's worktree is in that repository"
        (is (= spec-git-dir (:common-dir @observed)))
        (is (:spec-present? @observed)))
      (testing "given a spec in another repository → its task branch is created there"
        (is (str/starts-with? (str (:branch @observed)) task-branch-prefix))
        (is (some #{(:branch @observed)} (task-branches spec-repo))))
      (testing "given a spec in another repository → the working directory's repository gets no task branch"
        (is (empty? (task-branches cwd-repo)))))))

(phase/register-phase-defaults! probe-phase {:phase probe-phase :agent :workflow-tester
                                             :gates [] :budget probe-budget})
