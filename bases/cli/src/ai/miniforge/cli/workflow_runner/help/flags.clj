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
(ns ai.miniforge.cli.workflow-runner.help.flags
  "The `--help`/`-h` flag every workflow-runner subcommand carries: what
   it is, how a babashka.cli `:spec` gains it, and how it is stripped
   back out to render the `Options:` table inside the `--help` block.

   Stratification:
   Layer 0 — the help flag itself: its spec and its reserved keys.
   Layer 1 — adding it to a `:spec`, and the filter that strips it.
   Layer 2 — `flag-table` renders what survives the filter."
  (:require
   [babashka.cli :as cli]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private help-flag-spec
  "The `--help`/`-h` boolean flag added to every workflow-runner
   subcommand spec. Single definition keeps the alias consistent."
  {:coerce :boolean :alias :h})

(def ^{:stratum 0} help-flag-keys
  "Keys reserved for the help flag itself. Stripped from the usage
   table so `--help` doesn't appear inside its own description block."
  #{:help})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-help-flag
  "Return `spec` with the `--help`/`-h` boolean flag merged in. Idempotent."
  [spec]
  (assoc spec :help help-flag-spec))

(defn- ^{:stratum 1} without-help-flag
  "Drop the help flag from a babashka.cli `:spec` map so it doesn't
   appear inside the flag table its presence triggered."
  [spec]
  (into {} (remove (fn [[k _]] (contains? help-flag-keys k))) spec))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} flag-table
  "Auto-generate the flag table from a babashka.cli `:spec`. Returns
   nil when the (filtered) spec has no entries."
  [spec]
  (let [filtered (without-help-flag spec)]
    (when (seq filtered)
      (cli/format-opts {:spec filtered}))))
