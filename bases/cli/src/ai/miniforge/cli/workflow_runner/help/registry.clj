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
   - the babashka.cli `:spec` (flag definitions) wired into the
     `main.clj` dispatch table,
   - the per-subcommand `--help` usage block rendered by
     `ai.miniforge.cli.workflow-runner.help/usage-text`, and
   - the parent-level (`workflow --help` / `chain --help`) listing.

   Stratification:
   Layer 0 — flag-spec data + the `with-help-flag` helper.
   Layer 1 — the `subcommands` map + per-group ordering.
   Layer 2 — group-listing inputs derived from Layer 1."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^:private help-flag-spec
  "The `--help`/`-h` boolean flag added to every workflow-runner
   subcommand spec. Single definition keeps the alias consistent."
  {:coerce :boolean :alias :h})

(defn with-help-flag
  "Return `spec` with the `--help`/`-h` boolean flag merged in. Idempotent."
  [spec]
  (assoc spec :help help-flag-spec))

(def workflow-run-flag-spec
  (with-help-flag
    {:version       {:coerce :string  :alias :v :default "latest"}
     :input         {:alias :i}
     :input-json    {}
     :output        {:coerce :keyword :alias :o :default :pretty}
     :quiet         {:coerce :boolean :alias :q}
     :dashboard-url {:coerce :string  :alias :d}}))

(def workflow-list-flag-spec
  (with-help-flag {}))

(def workflow-execute-flag-spec
  (with-help-flag
    {:worktree       {:alias :w}
     :backend        {:coerce :keyword :alias :b}
     :execution-mode {:coerce :keyword :alias :m}
     :quiet          {:coerce :boolean :alias :q}}))

(def workflow-status-flag-spec
  (with-help-flag {}))

(def workflow-cancel-flag-spec
  (with-help-flag {}))

(def chain-run-flag-spec
  (with-help-flag
    {:version    {:coerce :string :alias :v :default "latest"}
     :spec       {:alias :s}
     :input-json {}
     :quiet      {:coerce :boolean :alias :q}}))

(def chain-list-flag-spec
  (with-help-flag {}))

;------------------------------------------------------------------------------ Layer 1

(def subcommands
  "Keyword-addressable registry of every workflow-runner subcommand.
   Each value is the input shape consumed by the help renderer."
  {:workflow-run     {:subcommand  "workflow run"
                      :summary-key :workflow-runner.help/workflow-run-summary
                      :spec        workflow-run-flag-spec
                      :positional  [:workflow-id]}
   :workflow-list    {:subcommand  "workflow list"
                      :summary-key :workflow-runner.help/workflow-list-summary
                      :spec        workflow-list-flag-spec
                      :positional  []}
   :workflow-execute {:subcommand  "workflow execute"
                      :summary-key :workflow-runner.help/workflow-execute-summary
                      :spec        workflow-execute-flag-spec
                      :positional  [:spec]}
   :workflow-status  {:subcommand  "workflow status"
                      :summary-key :workflow-runner.help/workflow-status-summary
                      :spec        workflow-status-flag-spec
                      :positional  [:id]}
   :workflow-cancel  {:subcommand  "workflow cancel"
                      :summary-key :workflow-runner.help/workflow-cancel-summary
                      :spec        workflow-cancel-flag-spec
                      :positional  [:id]}
   :chain-run        {:subcommand  "chain run"
                      :summary-key :workflow-runner.help/chain-run-summary
                      :spec        chain-run-flag-spec
                      :positional  [:chain-id]}
   :chain-list       {:subcommand  "chain list"
                      :summary-key :workflow-runner.help/chain-list-summary
                      :spec        chain-list-flag-spec
                      :positional  []}})

(def workflow-subcommand-keys
  "Display order for `mf workflow --help`."
  [:workflow-run :workflow-list :workflow-execute :workflow-status
   :workflow-cancel])

(def chain-subcommand-keys
  "Display order for `mf chain --help`."
  [:chain-run :chain-list])

(defn spec-for
  "Look up the babashka.cli `:spec` for a subcommand by key."
  [subcommand-key]
  (:spec (get subcommands subcommand-key)))

(defn entry-for
  "Look up the full registry entry for `subcommand-key`. Returns nil
   when unknown — callers may treat that as a programming error."
  [subcommand-key]
  (get subcommands subcommand-key))

;------------------------------------------------------------------------------ Layer 2

(defn- subcommand-leaf [entry]
  (-> (:subcommand entry) (str/split #"\s+") last))

(defn- group-subcommand-rows [entry-keys]
  (map (fn [entry-key]
         (let [entry (get subcommands entry-key)]
           {:name        (subcommand-leaf entry)
            :summary-key (:summary-key entry)}))
       entry-keys))

(def workflow-group-help
  "Input shape for the `workflow` parent's `--help` listing."
  {:group       "workflow"
   :summary-key :workflow-runner.help/workflow-summary
   :subcommands (group-subcommand-rows workflow-subcommand-keys)})

(def chain-group-help
  "Input shape for the `chain` parent's `--help` listing."
  {:group       "chain"
   :summary-key :workflow-runner.help/chain-summary
   :subcommands (group-subcommand-rows chain-subcommand-keys)})
