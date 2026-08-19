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
(ns ai.miniforge.cli.main.commands.etl.paths
  "Pack-path resolution shared by `etl run` and `etl validate`: turns the
   positional `pack-or-pipeline` argument and `--env` flag into absolute
   `pipeline-path` / `env-path` file paths. Extracted from
   `ai.miniforge.cli.main.commands.etl` (rule 210: the combined namespace
   measured 4 real layers, max 3)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} single-file-under
  "If exactly one .edn file lives under `dir/subdir`, return its abs path;
   otherwise nil (caller decides whether to error)."
  [dir subdir]
  (let [sub (fs/file dir subdir)]
    (when (fs/directory? sub)
      (let [ednfiles (->> (fs/glob sub "*.edn") (map fs/file))]
        (when (= 1 (count ednfiles))
          (str (first ednfiles)))))))

(defn- ^{:stratum 0} resolve-env-path
  "Resolve `--env`, which may be a `.edn` path or a bare env name that
   maps to `<pack-dir>/envs/<name>.edn`. Returns an absolute path or
   throws on an unresolvable input."
  [env pack-dir]
  (cond
    (nil? env)
    (response/throw-anomaly! :anomalies/incorrect
                             "missing --env <env.edn|name>"
                             {})

    (str/ends-with? env ".edn")
    (str (fs/absolutize env))

    pack-dir
    (let [candidate (fs/file pack-dir "envs" (str env ".edn"))]
      (if (fs/regular-file? candidate)
        (str (fs/absolutize candidate))
        (response/throw-anomaly! :anomalies/not-found
                                 (str "env not found: " candidate)
                                 {:env env :candidate (str candidate)})))

    :else
    (response/throw-anomaly! :anomalies/incorrect
                             (str "--env was a name but pipeline was given directly; pass a .edn path instead: " env)
                             {:env env})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resolve-pipeline-path
  "Resolve `pack-or-pipeline` into `[pack-dir pipeline-path]` as absolute
   paths. `pack-dir` is nil when the caller passed a pipeline EDN
   directly (no envs/ lookup possible)."
  [pack-or-pipeline]
  (let [f (fs/file pack-or-pipeline)]
    (cond
      (fs/directory? f)
      (if-let [p (single-file-under f "pipelines")]
        [(str (fs/absolutize f)) (str (fs/absolutize p))]
        (response/throw-anomaly! :anomalies/not-found
                                 (str "Could not find a single pipelines/*.edn under " f)
                                 {:pack-dir (str (fs/absolutize f))}))

      (and (fs/regular-file? f) (str/ends-with? (str f) ".edn"))
      [nil (str (fs/absolutize f))]

      :else
      (response/throw-anomaly! :anomalies/incorrect
                               (str "Not a pack directory or pipeline EDN: " pack-or-pipeline)
                               {:input pack-or-pipeline}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-pack-paths
  "Given the positional arg to `etl run` / `etl validate` and the `--env`
   flag, return `[pipeline-path env-path]` as absolute file paths, or
   throw ex-info on an unresolvable input.

   - If `pack-or-pipeline` is a directory, look for one `pipelines/*.edn`.
   - If it's an .edn file, use it as the pipeline.
   - `env` may be a path or a bare env name that resolves to
     `<pack>/envs/<name>.edn` when pack-or-pipeline is a directory."
  [pack-or-pipeline env]
  (let [[pack-dir pipeline-path] (resolve-pipeline-path pack-or-pipeline)
        env-path                 (resolve-env-path env pack-dir)]
    [pipeline-path env-path]))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (resolve-pack-paths "packs/data-foundry/github-data" "local")
  (resolve-pipeline-path "packs/data-foundry/github-data")
  :end)
