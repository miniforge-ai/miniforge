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
(ns ai.miniforge.cli.workflow-runner.help.registry
  "Pure data registry of every workflow-runner CLI subcommand
   (`workflow run/list/execute/status/cancel` and `chain run/list`).

   The registry is the single source of truth for:
   - the babashka.cli `:spec` (defined in `help/flag_specs.clj`) wired
     into the `main.clj` dispatch table,
   - the per-subcommand `--help` usage block rendered by
     `ai.miniforge.cli.workflow-runner.help/usage-text`, and
   - the parent-level (`workflow --help` / `chain --help`) listing.

   Stratification:
   Layer 0 — the `subcommands` map + per-group display ordering.
   Layer 1 — lookups and row derivations over that map.
   Layer 2 — the parent-level group-listing inputs."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.workflow-runner.help.flag-specs :as flag-specs]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} workflow-subcommand-keys
  "Display order for `mf workflow --help`."
  [:workflow-run :workflow-list :workflow-execute :workflow-status
   :workflow-inspect :workflow-cancel :workflow-gc-scratch])

(def ^{:stratum 0} chain-subcommand-keys
  "Display order for `mf chain --help`."
  [:chain-run :chain-list])

(defn- ^{:stratum 0} subcommand-leaf [entry]
  (-> (:subcommand entry) (str/split #"\s+") last))

(def ^{:stratum 0} subcommands
  "Keyword-addressable registry of every workflow-runner subcommand.
   Each value is the input shape consumed by the help renderer."
  {:workflow-run     {:subcommand  "workflow run"
                      :summary-key :workflow-runner.help/workflow-run-summary
                      :spec        flag-specs/workflow-run-flag-spec
                      :positional  [:workflow-id]}
   :workflow-list    {:subcommand  "workflow list"
                      :summary-key :workflow-runner.help/workflow-list-summary
                      :spec        flag-specs/workflow-list-flag-spec
                      :positional  []}
   :workflow-execute {:subcommand  "workflow execute"
                      :summary-key :workflow-runner.help/workflow-execute-summary
                      :spec        flag-specs/workflow-execute-flag-spec
                      :positional  [:spec]}
   :workflow-status  {:subcommand  "workflow status"
                      :summary-key :workflow-runner.help/workflow-status-summary
                      :spec        flag-specs/workflow-status-flag-spec
                      :positional  [:id]}
   :workflow-inspect {:subcommand  "workflow inspect"
                      :summary-key :workflow-runner.help/workflow-inspect-summary
                      :spec        flag-specs/workflow-inspect-flag-spec
                      :positional  [:path]}
   :workflow-cancel  {:subcommand  "workflow cancel"
                      :summary-key :workflow-runner.help/workflow-cancel-summary
                      :spec        flag-specs/workflow-cancel-flag-spec
                      :positional  [:id]}
   :workflow-gc-scratch {:subcommand  "workflow gc-scratch"
                         :summary-key :workflow-runner.help/workflow-gc-scratch-summary
                         :spec        flag-specs/workflow-gc-scratch-flag-spec
                         :positional  []}
   :chain-run        {:subcommand  "chain run"
                      :summary-key :workflow-runner.help/chain-run-summary
                      :spec        flag-specs/chain-run-flag-spec
                      :positional  [:chain-id]}
   :chain-list       {:subcommand  "chain list"
                      :summary-key :workflow-runner.help/chain-list-summary
                      :spec        flag-specs/chain-list-flag-spec
                      :positional  []}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} spec-for
  "Look up the babashka.cli `:spec` for a subcommand by key."
  [subcommand-key]
  (:spec (get subcommands subcommand-key)))

(defn ^{:stratum 1} entry-for
  "Look up the full registry entry for `subcommand-key`. Returns nil
   when unknown — callers may treat that as a programming error."
  [subcommand-key]
  (get subcommands subcommand-key))

(defn- ^{:stratum 1} group-subcommand-rows [entry-keys]
  (map (fn [entry-key]
         (let [entry (get subcommands entry-key)]
           {:name        (subcommand-leaf entry)
            :summary-key (:summary-key entry)}))
       entry-keys))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} workflow-group-help
  "Input shape for the `workflow` parent's `--help` listing."
  {:group       "workflow"
   :summary-key :workflow-runner.help/workflow-summary
   :subcommands (group-subcommand-rows workflow-subcommand-keys)})

(def ^{:stratum 2} chain-group-help
  "Input shape for the `chain` parent's `--help` listing."
  {:group       "chain"
   :summary-key :workflow-runner.help/chain-summary
   :subcommands (group-subcommand-rows chain-subcommand-keys)})
