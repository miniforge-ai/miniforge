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
(ns ai.miniforge.cli.workflow-runner.help.usage
  "Localized usage blocks rendered from a registry entry: the
   per-subcommand block behind `mf workflow run --help` and the
   parent listing behind `mf workflow --help`.

   Rendering only — the strings are returned, never printed.

   Stratification:
   Layer 0 — the usage banner line and the group-level block.
   Layer 1 — `usage-text` composes heading, summary, banner and the
             generated flag table into the subcommand block."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.help.flags :as flags]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} usage-line
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

(defn ^{:stratum 0} group-usage-text
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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} usage-text
  "Render the full localized usage block for a registry entry."
  [{:keys [subcommand summary-key spec positional]}]
  (let [usage   (messages/t :workflow-runner.help/usage-prefix
                            {:line (usage-line subcommand positional)})
        summary (messages/t summary-key)
        table   (flags/flag-table spec)]
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
