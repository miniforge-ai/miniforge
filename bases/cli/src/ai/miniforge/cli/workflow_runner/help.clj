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

   Stratification:
   Layer 0 — pure predicates and the babashka.cli `:spec` filter that
             strips the help-flag entry so it doesn't appear in its own
             usage block.
   Layer 1 — `usage-text` / `group-usage-text` compose localized
             usage strings from a registry entry.
   Layer 2 — `print-usage!` / `print-group-usage!` are the
             side-effecting handlers and the `handler-with-help` /
             `group-handler` factories the dispatch table wires in."
  (:require
   [babashka.cli :as cli]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.help.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

(def help-flag-keys
  "Keys reserved for the help flag itself. Stripped from the usage
   table so `--help` doesn't appear inside its own description block."
  #{:help})

(defn help-requested?
  "Truthy when the parsed `opts` map carries the `--help` / `-h` flag."
  [opts]
  (boolean (:help opts)))

(defn- without-help-flag
  "Drop the help flag from a babashka.cli `:spec` map so it doesn't
   appear inside the flag table its presence triggered."
  [spec]
  (into {} (remove (fn [[k _]] (contains? help-flag-keys k))) spec))

(defn- usage-line
  "First-line usage banner for a subcommand."
  [subcommand positional]
  (let [binary (app-config/binary-name)
        positionals (when (seq positional)
                      (->> positional
                           (map (fn [k] (str "<" (name k) ">")))
                           (str/join " ")))]
    (->> [binary subcommand positionals "[options]"]
         (remove str/blank?)
         (str/join " "))))

(defn- flag-table
  "Auto-generate the flag table from a babashka.cli `:spec`. Returns
   nil when the (filtered) spec has no entries."
  [spec]
  (let [filtered (without-help-flag spec)]
    (when (seq filtered)
      (cli/format-opts {:spec filtered}))))

;------------------------------------------------------------------------------ Layer 1

(defn usage-text
  "Render the full localized usage block for a registry entry."
  [{:keys [subcommand summary-key spec positional]}]
  (let [usage   (messages/t :workflow-runner.help/usage-prefix
                            {:line (usage-line subcommand positional)})
        summary (messages/t summary-key)
        table   (flag-table spec)]
    (->> [(messages/t :workflow-runner.help/subcommand-heading
                      {:subcommand subcommand})
          summary
          ""
          usage
          (when table
            (messages/t :workflow-runner.help/options-heading))
          table]
         (remove nil?)
         (str/join "\n"))))

(defn group-usage-text
  "Render the localized parent-level usage block for a subcommand group."
  [{:keys [group summary-key subcommands]}]
  (let [usage    (messages/t :workflow-runner.help/group-usage-prefix
                             {:line (str (app-config/binary-name)
                                         " "
                                         group
                                         " <subcommand> [options]")})
        summary  (messages/t summary-key)
        row-text (fn [entry]
                   (messages/t :workflow-runner.help/group-subcommand-row
                               {:name    (:name entry)
                                :summary (messages/t (:summary-key entry))}))
        rows     (map row-text subcommands)]
    (->> [(messages/t :workflow-runner.help/group-heading {:group group})
          summary
          ""
          usage
          ""
          (messages/t :workflow-runner.help/group-subcommands-heading)
          (str/join "\n" rows)]
         (str/join "\n"))))

;------------------------------------------------------------------------------ Layer 2

(defn print-usage!
  "Print the localized usage block for `entry` to stdout."
  [entry]
  (println (usage-text entry)))

(defn print-group-usage!
  "Print the localized parent-level usage block for `group-entry`."
  [group-entry]
  (println (group-usage-text group-entry)))

(defn handler-with-help
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

(defn group-handler
  "Return a dispatch handler that prints the group-level usage block
   for `group-entry` (e.g. `registry/workflow-group-help`)."
  [group-entry]
  (fn group-help-handler [_m]
    (print-group-usage! group-entry)))
