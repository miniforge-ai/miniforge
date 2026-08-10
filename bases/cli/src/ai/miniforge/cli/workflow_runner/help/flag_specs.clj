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
(ns ai.miniforge.cli.workflow-runner.help.flag-specs
  "The babashka.cli `:spec` each workflow-runner subcommand accepts —
   one def per subcommand, every one carrying the `--help`/`-h` flag.

   Pure data. `help/registry.clj` binds these to subcommand keys; the
   flag vocabulary they are written in lives in `help/flags.clj`.

   Stratification:
   Layer 0 — one flag spec per subcommand."
  (:require
   [ai.miniforge.cli.workflow-runner.help.flags :as flags]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} workflow-run-flag-spec
  (flags/with-help-flag
    {:version       {:coerce :string  :alias :v :default "latest"}
     :input         {:alias :i}
     :input-json    {}
     :output        {:coerce :keyword :alias :o :default :pretty}
     :quiet         {:coerce :boolean :alias :q}
     :dashboard-url {:coerce :string  :alias :d}}))

(def ^{:stratum 0} workflow-list-flag-spec
  (flags/with-help-flag {}))

(def ^{:stratum 0} workflow-execute-flag-spec
  (flags/with-help-flag
    {:worktree       {:alias :w}
     :backend        {:coerce :keyword :alias :b}
     :execution-mode {:coerce :keyword :alias :m}
     :quiet          {:coerce :boolean :alias :q}}))

(def ^{:stratum 0} workflow-status-flag-spec
  (flags/with-help-flag {}))

(def ^{:stratum 0} workflow-inspect-flag-spec
  (flags/with-help-flag {}))

(def ^{:stratum 0} workflow-cancel-flag-spec
  (flags/with-help-flag {}))

(def ^{:stratum 0} workflow-gc-scratch-flag-spec
  (flags/with-help-flag
    {:max-age-days {:coerce :int :alias :a :default 7}
     :repo-path    {:coerce :string :alias :r}}))

(def ^{:stratum 0} chain-run-flag-spec
  (flags/with-help-flag
    {:version    {:coerce :string :alias :v :default "latest"}
     :spec       {:alias :s}
     :input-json {}
     :quiet      {:coerce :boolean :alias :q}}))

(def ^{:stratum 0} chain-list-flag-spec
  (flags/with-help-flag {}))
