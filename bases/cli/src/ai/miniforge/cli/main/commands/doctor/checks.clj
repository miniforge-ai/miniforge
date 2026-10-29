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
(ns ai.miniforge.cli.main.commands.doctor.checks
  "The checks behind `mf doctor`, returned as data.

   Required, because a run fails without them, most of them only in the
   release phase after the LLM spend:
   - git, with user.name and user.email set (host-side commits use them)
   - gh, logged in (the release phase opens the PR with it)
   - the LLM backend's CLI, when that backend runs through one

   The container runtime is required only for an execution mode whose
   tasks run in a container capsule, and reported as optional otherwise.
   The user config file is optional: defaults apply without it.

   Every external command runs with `check-timeout-ms`, so a hung tool
   fails its check instead of hanging the doctor.

   A check result:
     {:check/id        keyword
      :check/label     string
      :check/required? boolean
      :check/ok?       boolean
      :check/detail    string
      :check/details   [string]}   ; extra lines: hints, probe results

   A command check spec is a result without :check/ok? and :check/detail,
   plus :check/argv, and optionally :check/ok-detail (shown instead of the
   command's output on success) and :check/hint (shown on failure)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.runtime :as cmd-runtime]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.process :as process]
   [ai.miniforge.llm.interface :as llm]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} check-timeout-ms
  "Upper bound on each external command the doctor runs."
  10000)

(def ^{:stratum 0} capsule-execution-modes
  "Execution modes whose tasks run in a container capsule (see
   `workflow.runner-environment/registry-config-for-mode`). Every other
   mode runs tasks in a host worktree."
  #{:governed})

(defn ^{:stratum 0} check-result
  [id label required? ok? detail]
  {:check/id        id
   :check/label     label
   :check/required? required?
   :check/ok?       ok?
   :check/detail    detail
   :check/details   []})

(defn ^{:stratum 0} command-spec
  "A required check that passes when `argv` exits 0 within the timeout."
  [id label argv & {:keys [ok-detail hint]}]
  (cond-> {:check/id        id
           :check/label     label
           :check/required? true
           :check/argv      argv}
    ok-detail (assoc :check/ok-detail ok-detail)
    hint      (assoc :check/hint hint)))

(defn ^{:stratum 0} output-line
  "First line of a command's stdout, else of its stderr, else \"\"."
  [{:keys [out err]}]
  (or (some->> [out err]
               (keep #(some-> % str/trim not-empty))
               first
               str/split-lines
               first)
      ""))

(defn ^{:stratum 0} failed-detail
  "Detail for `cmd` exiting non-zero; `line` is its first output line."
  [cmd exit line]
  (if (str/blank? line)
    (messages/t :doctor/command-failed-silent {:cmd cmd :exit exit})
    (messages/t :doctor/command-failed {:cmd cmd :exit exit :output line})))

(defn ^{:stratum 0} finish-check
  "Turn a command check spec and its {:ok? :detail} outcome into a result."
  [spec {:keys [ok? detail]}]
  (let [hint    (:check/hint spec)
        detail  (if ok? (get spec :check/ok-detail detail) detail)
        details (if (and hint (not ok?)) [hint] [])]
    (-> spec
        (dissoc :check/argv :check/ok-detail :check/hint)
        (assoc :check/ok? ok?
               :check/detail detail
               :check/details details))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} git-identity-spec
  [config-key]
  (command-spec (keyword "git" config-key)
                (messages/t :doctor/label-git-identity {:key config-key})
                ["git" "config" config-key]
                :hint (messages/t :doctor/hint-git-identity {:key config-key})))

(defn ^{:stratum 1} gh-auth-spec
  []
  (command-spec :gh-auth
                (messages/t :doctor/label-gh-auth)
                ["gh" "auth" "status"]
                :ok-detail (messages/t :doctor/gh-logged-in)
                :hint (messages/t :doctor/hint-gh-auth)))

(defn ^{:stratum 1} run-command
  "Run `argv` with `check-timeout-ms`. Returns nil when the program is not
   on PATH, else {:exit :out :err}, plus :timeout-ms when it was killed."
  [argv]
  (when-let [path (fs/which (first argv))]
    (try+
      (process/run-cli-command (into [(str path)] (rest argv)) check-timeout-ms)
      (catch java.io.IOException e
        {:exit -1 :out "" :err (ex-message e)}))))

(defn ^{:stratum 1} command-outcome
  "Judge a run-command result for `cmd`: {:ok? bool :detail string}."
  [cmd result]
  (let [{:keys [exit timeout-ms]} result
        line (output-line result)]
    (cond
      (nil? result) {:ok? false :detail (messages/t :doctor/not-found {:cmd cmd})}
      timeout-ms    {:ok? false :detail (messages/t :doctor/timed-out {:cmd cmd :timeout-ms timeout-ms})}
      (zero? exit)  {:ok? true :detail line}
      :else         {:ok? false :detail (failed-detail cmd exit line)})))

(defn ^{:stratum 1} backend-spec
  "Check for the LLM `backend`: its CLI's `--version` when it runs through
   a CLI; already decided when it is unknown or needs no CLI."
  [backend]
  (let [{:keys [cmd requires-cli?] :as entry} (get llm/backends backend)
        label (messages/t :doctor/label-backend {:backend (name backend)})]
    (cond
      (nil? entry)  (check-result :backend label true false
                                  (messages/t :doctor/backend-unknown
                                              {:command (app-config/command-string "config backends")}))
      requires-cli? (command-spec :backend label [cmd "--version"])
      :else         (check-result :backend label true true (messages/t :doctor/backend-no-cli)))))

(defn ^{:stratum 1} runtime-check
  "The container runtime, required when `execution-mode` runs tasks in a
   container capsule."
  [execution-mode]
  (let [required? (contains? capsule-execution-modes execution-mode)
        {:keys [ok? summary details]} (cmd-runtime/doctor-runtime-report)
        details (if required? details (conj details (messages/t :doctor/runtime-optional)))]
    (assoc (check-result :runtime (messages/t :doctor/label-runtime) required? ok? summary)
           :check/details details)))

(defn ^{:stratum 1} config-check
  [config-path]
  (let [exists? (fs/exists? config-path)
        detail  (if exists?
                  (messages/t :doctor/config-exists {:config-path config-path})
                  (messages/t :doctor/no-config {:command (app-config/command-string "config init")}))]
    (check-result :config (messages/t :doctor/label-config) false exists? detail)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} command-check-specs
  "The command-backed checks for `backend`, in display order."
  [backend]
  [(command-spec :git (messages/t :doctor/label-git) ["git" "--version"])
   (git-identity-spec "user.name")
   (git-identity-spec "user.email")
   (command-spec :gh (messages/t :doctor/label-gh) ["gh" "--version"])
   (gh-auth-spec)
   (backend-spec backend)])

(defn ^{:stratum 2} command-check
  "Run a command check spec and return its result. A spec that already
   carries :check/ok? was decided without a command and is returned as is."
  [spec]
  (if (contains? spec :check/ok?)
    spec
    (let [argv (:check/argv spec)]
      (finish-check spec (command-outcome (first argv) (run-command argv))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (map command-check (command-check-specs :opencode))
  (runtime-check :governed)
  (config-check "/nonexistent/config.edn")

  :end)
