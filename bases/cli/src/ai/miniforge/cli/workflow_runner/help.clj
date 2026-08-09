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
(ns ai.miniforge.cli.workflow-runner.help
  "Per-subcommand `--help` rendering for the workflow-runner-facing CLI
   subcommands (`workflow run/list/execute/status/cancel` and
   `chain run/list`).

   The usage strings themselves live in `...help.usage`; this namespace
   is the dispatch-facing side — flag detection, printing, and the
   handler factories `main.clj` wires into its dispatch table.

   Stratification:
   Layer 0 — the `--help` predicate and the stdout printers.
   Layer 1 — the `handler-with-help` / `group-handler` factories the
             dispatch table wires in."
  (:require
   [ai.miniforge.cli.workflow-runner.help.registry :as registry]
   [ai.miniforge.cli.workflow-runner.help.usage :as usage]))

;------------------------------------------------------------------------------ Layer 0

;; Relocated public vars re-exported so existing `:require [...
;; workflow-runner.help :as wr-help]` call sites resolve unchanged —
;; same convention as the workflow_runner.clj split (miniforge#1667).
(def ^{:stratum 0} usage-text usage/usage-text)

(def ^{:stratum 0} group-usage-text usage/group-usage-text)

(defn ^{:stratum 0} help-requested?
  "Truthy when the parsed `opts` map carries the `--help` / `-h` flag."
  [opts]
  (boolean (:help opts)))

(defn ^{:stratum 0} print-usage!
  "Print the localized usage block for `entry` to stdout."
  [entry]
  (println (usage/usage-text entry)))

(defn ^{:stratum 0} print-group-usage!
  "Print the localized parent-level usage block for `group-entry`."
  [group-entry]
  (println (usage/group-usage-text group-entry)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} group-handler
  "Return a dispatch handler that prints the group-level usage block
   for `group-entry` (e.g. `registry/workflow-group-help`)."
  [group-entry]
  (fn group-help-handler [_m]
    (print-group-usage! group-entry)))

(defn ^{:stratum 1} handler-with-help
  "Wrap a dispatch handler `cmd-fn` so that `--help` / `-h` short-circuits
   to a localized usage block. `subcommand-key` is one of the keys in
   `registry/subcommands` (e.g. `:workflow-run`)."
  [subcommand-key cmd-fn]
  (let [entry (registry/entry-for subcommand-key)]
    (when-not entry
      (throw (ex-info "Unknown workflow-runner help subcommand-key"
                      {:subcommand-key subcommand-key
                       :known-keys (keys registry/subcommands)})))
    (fn dispatch-handler [m]
      (let [opts (if (contains? m :opts) (:opts m) m)]
        (if (help-requested? opts)
          (print-usage! entry)
          (cmd-fn m))))))
