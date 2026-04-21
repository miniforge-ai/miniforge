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

   The `etl repo` command delegates to `ai.miniforge.etl-pipe.interface`
   when available. The `etl run|list|validate` commands shell out to
   `ai.miniforge.etl.main` on the JVM."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers — shared by etl repo

(defn validate-git-url
  "Return true when url begins with a recognised git transport prefix."
  [url]
  (boolean
   (or (str/starts-with? url "https://")
       (str/starts-with? url "git@")
       (str/starts-with? url "ssh://")
       (str/starts-with? url "http://"))))

(defn- git-clone-temp
  "Shallow-clone `url` into a temporary directory.
   Returns a schema/success or schema/failure result."
  [url]
  (try
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/miniforge-etl-"
                       (System/currentTimeMillis))
          result  (process/sh "git" "clone" "--depth" "1" url tmp-dir)]
      (if (zero? (:exit result))
        (schema/success :path tmp-dir)
        (schema/failure :path (str/trim (:err result)))))
    (catch Exception e
      (schema/failure :path (ex-message e)))))

;------------------------------------------------------------------------------ Layer 1
;; Pack-path resolution (shared by run + validate)

(defn- single-file-under
  "If exactly one .edn file lives under `dir/subdir`, return its abs path;
   otherwise nil (caller decides whether to error)."
  [dir subdir]
  (let [sub (fs/file dir subdir)]
    (when (fs/directory? sub)
      (let [ednfiles (->> (fs/glob sub "*.edn") (map fs/file))]
        (when (= 1 (count ednfiles))
          (str (first ednfiles)))))))

(defn- resolve-pack-paths
  "Given the positional arg to `etl run` / `etl validate` and the `--env`
   flag, return `[pipeline-path env-path]` as absolute file paths, or
   throw ex-info on an unresolvable input.

   - If `pack-or-pipeline` is a directory, look for one `pipelines/*.edn`.
   - If it's an .edn file, use it as the pipeline.
   - `env` may be a path or a bare env name that resolves to
     `<pack>/envs/<name>.edn` when pack-or-pipeline is a directory."
  [pack-or-pipeline env]
  (let [f (fs/file pack-or-pipeline)
        [pack-dir pipeline-path]
        (cond
          (fs/directory? f)
          (if-let [p (single-file-under f "pipelines")]
            [(str f) p]
            (throw (ex-info (str "Could not find a single pipelines/*.edn under " f) {})))

          (and (fs/regular-file? f) (str/ends-with? (str f) ".edn"))
          [nil (str f)]

          :else
          (throw (ex-info (str "Not a pack directory or pipeline EDN: " pack-or-pipeline) {})))
        env-path
        (cond
          (nil? env)
          (throw (ex-info "missing --env <env.edn|name>" {}))

          (str/ends-with? env ".edn")
          (str (fs/absolutize env))

          pack-dir
          (let [candidate (fs/file pack-dir "envs" (str env ".edn"))]
            (if (fs/regular-file? candidate)
              (str candidate)
              (throw (ex-info (str "env not found: " candidate) {}))))

          :else
          (throw (ex-info (str "--env was a name but pipeline was given directly; pass a .edn path instead: " env) {})))]
    [pipeline-path env-path]))

;------------------------------------------------------------------------------ Layer 2
;; JVM shell-out (run / list / validate)

(defn- miniforge-checkout?
  "The JVM entry ships only as a :dev-alias local/root today, so the
   command has to run from inside a miniforge checkout until we publish
   the etl base as an installable artifact."
  []
  (and (fs/exists? "deps.edn")
       (fs/exists? "bases/etl/deps.edn")))

(defn- shell-etl!
  "Shell out to the JVM etl entry point. `args` are the post-`-m` args:
   the subcommand name and its flags. Streams stdout/stderr to the
   user's terminal. Returns the subprocess exit code."
  [args]
  (if-not (miniforge-checkout?)
    (do (display/print-error "mf etl run/list/validate currently requires running from a miniforge checkout (the etl base is dev-alias-only).")
        1)
    (let [argv (into ["clojure" "-M:dev" "-m" "ai.miniforge.etl.main"] args)
          {:keys [exit]} (deref (process/process argv {:out :inherit :err :inherit}))]
      exit)))

;------------------------------------------------------------------------------ Layer 3
;; Fallback analysis (etl repo)

(defn- etl-repo-fallback
  "Clone repo and run repo-analyzer as ETL fallback."
  [url]
  (let [clone-result (git-clone-temp url)]
    (if (schema/failed? clone-result)
      (display/print-error (messages/t :etl/clone-failed {:error (:error clone-result)}))
      (let [repo-path (:path clone-result)]
        (try
          (let [analyze-fn (requiring-resolve 'ai.miniforge.cli.repo-analyzer/analyze-repo)
                analysis   (analyze-fn repo-path)]
            (display/print-success (messages/t :etl/analysis-complete))
            (println (messages/t :etl/technologies {:value (pr-str (:technologies analysis))}))
            (println (messages/t :etl/git-host {:value (get analysis :git-host "unknown")}))
            (println (messages/t :etl/packs {:value (pr-str (:packs analysis))}))
            (println)
            (println (display/style (messages/t :etl/install-note) :foreground :yellow)))
          (catch Exception e
            (display/print-error (messages/t :etl/analysis-failed {:error (ex-message e)})))
          (finally
            (try (fs/delete-tree repo-path)
                 (catch Exception _ nil))))))))

;------------------------------------------------------------------------------ Layer 4
;; Command implementations

(defn etl-repo-cmd
  "Run the ETL pipeline against a git repository URL.

   Clones the repository, extracts structured metadata (languages, packs,
   dependency graph, symbol index), and persists the result to the artifacts
   directory for use by downstream analysis commands.

   Usage: miniforge etl repo <url>"
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (shared/usage-error! :etl/repo-usage "etl repo <url>")
      (if-not (validate-git-url url)
        (do (display/print-error (messages/t :etl/invalid-url {:url url}))
            (shared/exit! 1))
        (do
          (display/print-info (messages/t :etl/running {:url url}))
          (let [result (shared/try-resolve-fn 'ai.miniforge.etl-pipe.interface/etl-repo url opts)]
            (cond
              (and result (schema/succeeded? result))
              (do
                (display/print-success (messages/t :etl/complete))
                (when-let [artifacts (:artifacts result)]
                  (println (messages/t :etl/artifacts-produced {:count (count artifacts)})))
                (when-let [path (:output-path result)]
                  (println (messages/t :etl/output-path {:path path}))))

              (and result (schema/failed? result))
              (display/print-error (messages/t :etl/failed {:error (get result :error "unknown error")}))

              :else
              (etl-repo-fallback url))))))))

(defn etl-run-cmd
  "Execute a Data Foundry ETL pack.

   Usage:
     miniforge etl run <pack-dir-or-pipeline.edn> --env <env.edn|name> [--out <result.edn|.json>]

   When the first arg is a pack directory, the command looks for a single
   `pipelines/*.edn` file and, if `--env` is a bare name, resolves it as
   `<pack>/envs/<name>.edn`. Otherwise both arguments are used as file
   paths directly."
  [opts]
  (let [{:keys [pack env out]} opts]
    (if-not pack
      (shared/usage-error! :etl/run-usage
                           "etl run <pack-dir-or-pipeline.edn> --env <env.edn|name> [--out <path>]")
      (try
        (let [[pipeline-path env-path] (resolve-pack-paths pack env)
              args (cond-> ["run" pipeline-path "--env" env-path]
                     out (into ["--out" out]))]
          (shared/exit! (shell-etl! args)))
        (catch clojure.lang.ExceptionInfo e
          (display/print-error (ex-message e))
          (shared/exit! 1))))))

(defn etl-list-cmd
  "List pipeline EDN files discovered under a search path.

   Usage: miniforge etl list [<search-path>]
          (defaults to `.`)"
  [opts]
  (let [path (or (:paths opts) ".")]
    (shared/exit! (shell-etl! ["list" path]))))

(defn etl-validate-cmd
  "Load + resolve a pack without executing. Surfaces loader, env, or
   resolver errors.

   Usage: miniforge etl validate <pack-dir-or-pipeline.edn> --env <env.edn|name>"
  [opts]
  (let [{:keys [pack env]} opts]
    (if-not pack
      (shared/usage-error! :etl/validate-usage
                           "etl validate <pack-dir-or-pipeline.edn> --env <env.edn|name>")
      (try
        (let [[pipeline-path env-path] (resolve-pack-paths pack env)]
          (shared/exit! (shell-etl! ["validate" pipeline-path "--env" env-path])))
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
