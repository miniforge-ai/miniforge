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
(ns ai.miniforge.cli.main.commands.etl
  "ETL commands:
     - `etl repo <url>`                — clone+analyze a git repository
                                         (structural extraction; BB-side).
     - `etl run <pack> --env <env>`    — execute a Data Foundry pack's
                                         pipeline. Shells out to JVM
                                         because source connectors use
                                         hato/POI which aren't BB-safe.
     - `etl list <search-path>`        — discover pipeline EDN files.
     - `etl validate <pack> --env …`   — load + resolve without running.
     - `etl registry --out <path>`     — export the Miniforge ETL workbench
                                         state-variable registry.

   The `etl repo` command clones the repository and runs the direct
   repo-analyzer interface. The `etl run|list|validate` commands shell
   out to `ai.miniforge.etl.main` on the JVM.

   Git-URL validation/cloning, JVM shell-out, and pack-path resolution
   live in sibling `ai.miniforge.cli.main.commands.etl.*` namespaces
   (rule 210: the combined namespace measured 4 real layers, max 3).
   With those hops no longer counted toward this namespace's own local
   layer depth, the five command entry points below don't call each
   other — each calls straight into a sibling namespace — so they all
   measure a single layer."
  (:require
   [ai.miniforge.cli.main.commands.etl.paths :as etl-paths]
   [ai.miniforge.cli.main.commands.etl.repo :as etl-repo]
   [ai.miniforge.cli.main.commands.etl.shell :as etl-shell]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

;; Command implementations
(defn ^{:stratum 0} etl-repo-cmd
  "Run the ETL pipeline against a git repository URL.

   Clones the repository, extracts structured metadata (languages, packs,
   dependency graph, symbol index), and persists the result to the artifacts
   directory for use by downstream analysis commands.

   Usage: miniforge etl repo <url>"
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (shared/usage-error! :etl/repo-usage "etl repo <url>")
      (if-not (etl-repo/validate-git-url url)
        (do (display/print-error (messages/t :etl/invalid-url {:url url}))
            (shared/exit! 1))
        (do
          (display/print-info (messages/t :etl/running {:url url}))
          (let [exit-code (etl-repo/analyze-repo-url! url)]
            (when (pos? exit-code)
              (shared/exit! exit-code))))))))

(defn ^{:stratum 0} etl-list-cmd
  "List pipeline EDN files discovered under a search path.

   Usage: miniforge etl list [<search-path>]
          (defaults to `.`)"
  [opts]
  (let [path (get opts :paths ".")]
    (shared/exit! (etl-shell/shell-etl! ["list" path]))))

(defn ^{:stratum 0} etl-registry-cmd
  "Export the product-owned ETL state-variable registry as EDN or JSON."
  [opts]
  (if-let [out (:out opts)]
    (shared/exit! (etl-shell/shell-etl! ["registry" "--out" out]))
    (shared/usage-error! :etl/registry-usage
                         "etl registry --out <registry.edn|.json>")))

(defn ^{:stratum 0} etl-run-cmd
  "Execute a Data Foundry ETL pack.

   Usage:
     miniforge etl run <pack-dir-or-pipeline.edn> --env <env.edn|name>
       [--out <result.edn|.json>]
       [--workbench-out <snapshot.json> --experiment-id <id> --label <label>
        --source-hash <sha256:...> [--baseline <snapshot.json>]]

   When the first arg is a pack directory, the command looks for a single
   `pipelines/*.edn` file and, if `--env` is a bare name, resolves it as
   `<pack>/envs/<name>.edn`. Otherwise both arguments are used as file
   paths directly."
  [opts]
  (let [{:keys [pack env out workbench-out experiment-id label source-hash
                baseline snapshot-id run-id]} opts]
    (if-not pack
      (shared/usage-error! :etl/run-usage
                           (str "etl run <pack-dir-or-pipeline.edn> --env <env.edn|name> [--out <path>]"
                                " [--workbench-out <snapshot.json> --experiment-id <id> --label <label>"
                                " --source-hash <sha256:...> [--baseline <snapshot.json>]]"))
      (try
        (let [[pipeline-path env-path] (etl-paths/resolve-pack-paths pack env)
              args (cond-> ["run" pipeline-path "--env" env-path]
                     out            (into ["--out" out])
                     workbench-out  (into ["--workbench-out" workbench-out])
                     experiment-id  (into ["--experiment-id" experiment-id])
                     label          (into ["--label" label])
                     source-hash    (into ["--source-hash" source-hash])
                     baseline       (into ["--baseline" baseline])
                     snapshot-id    (into ["--snapshot-id" snapshot-id])
                     run-id         (into ["--run-id" run-id]))]
          (shared/exit! (etl-shell/shell-etl! args)))
        (catch clojure.lang.ExceptionInfo e
          (display/print-error (ex-message e))
          (shared/exit! 1))))))

(defn ^{:stratum 0} etl-validate-cmd
  "Load + resolve a pack without executing. Surfaces loader, env, or
   resolver errors.

   Usage: miniforge etl validate <pack-dir-or-pipeline.edn> --env <env.edn|name>"
  [opts]
  (let [{:keys [pack env]} opts]
    (if-not pack
      (shared/usage-error! :etl/validate-usage
                           "etl validate <pack-dir-or-pipeline.edn> --env <env.edn|name>")
      (try
        (let [[pipeline-path env-path] (etl-paths/resolve-pack-paths pack env)]
          (shared/exit! (etl-shell/shell-etl! ["validate" pipeline-path "--env" env-path])))
        (catch clojure.lang.ExceptionInfo e
          (display/print-error (ex-message e))
          (shared/exit! 1))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (etl-repo-cmd {:url "https://github.com/miniforge-ai/miniforge"})
  (etl-run-cmd {:pack "packs/data-foundry/github-data" :env "local"})
  (etl-list-cmd {:paths ["packs/data-foundry"]})
  (etl-validate-cmd {:pack "packs/data-foundry/github-data" :env "local"})
  :end)
