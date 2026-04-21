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

(ns ai.miniforge.etl.runner
  "Orchestrates one pack execution: load pipeline + env EDNs, instantiate
   connectors from the pack-declared `:env/connectors`, resolve the
   pipeline against the env's stage overrides, and hand off to
   `pipeline-runner/execute-pipeline`.

   Every piece of configuration (which connector type, which auth
   credential, which output destination) stays in the pack; the runner
   just wires pack data into live instances."
  (:require
   [ai.miniforge.etl.registry :as registry]
   [ai.miniforge.pipeline-config.interface :as pc]
   [ai.miniforge.pipeline-runner.interface :as pr-run]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Env + pipeline assembly

(defn- load-inputs
  "Load and sanity-check the pipeline EDN and env EDN. Returns
   `{:success? true :pipeline <edn> :env-config <edn>}` on success,
   or the first schema/failure encountered."
  [pipeline-path env-path]
  (let [pipeline (pc/load-pipeline pipeline-path)]
    (if (schema/failed? pipeline)
      pipeline
      (let [env (pc/load-env-config env-path)]
        (if (schema/failed? env)
          env
          {:success?   true
           :pipeline   (:pipeline pipeline)
           :env-config (:env-config env)})))))

(defn- unsupported-types
  "Connector types referenced by the env that the registry can't
   construct. Returns a vector of keywords."
  [env-conn-types]
  (let [supported (set (registry/supported-types))]
    (->> (vals env-conn-types) distinct (remove supported) vec)))

;------------------------------------------------------------------------------ Layer 1
;; Public entry

(defn run-pack
  "Execute the pipeline at `pipeline-path` using the environment at
   `env-path`. `context` is passed through to
   `pipeline-runner/execute-pipeline` (intended for event-stream, log
   sinks, etc.).

   Returns the pipeline-runner result map (schema/success or
   schema/failure with `:pipeline-run`)."
  ([pipeline-path env-path]
   (run-pack pipeline-path env-path {}))
  ([pipeline-path env-path context]
   (let [loaded (load-inputs pipeline-path env-path)]
     (if-not (:success? loaded)
       loaded
       (let [{:keys [pipeline env-config]} loaded
             env-conn-types                (pc/extract-connector-types env-config)
             unknown                       (unsupported-types env-conn-types)]
         (if (seq unknown)
           (schema/failure
             :pipeline-run
             (str "Env references connector types not supported by this runner: "
                  (pr-str unknown)
                  ". Supported: " (pr-str (registry/supported-types))))
           (let [reg            (registry/build-registry)
                 instantiated   (pc/instantiate-connectors reg env-conn-types)
                 stage-configs  (pc/extract-stage-configs env-config)
                 res-ctx        (pc/create-resolution-context
                                  {:connector-refs (:connector-refs instantiated)
                                   :stage-configs  stage-configs})
                 resolved       (pc/resolve-pipeline pipeline res-ctx)]
             (pr-run/execute-pipeline resolved (:connectors instantiated) context))))))))

;------------------------------------------------------------------------------ Layer 2
;; Pack-level helpers (pipeline discovery + validation)

(defn list-pipelines
  "Discover pipeline EDNs under one or more search paths. Passes through
   to `pipeline-config/discover-pipelines`."
  [search-paths]
  (pc/discover-pipelines search-paths))

(defn validate-pack
  "Load + resolve the pipeline at `pipeline-path` with `env-path` without
   executing it. Surfaces any loader, env, or resolver failure."
  [pipeline-path env-path]
  (let [loaded (load-inputs pipeline-path env-path)]
    (if-not (:success? loaded)
      loaded
      (let [{:keys [pipeline env-config]} loaded
            env-conn-types                (pc/extract-connector-types env-config)
            unknown                       (unsupported-types env-conn-types)]
        (if (seq unknown)
          (schema/failure :pipeline-run (str "Unsupported connector types: " (pr-str unknown)))
          (let [reg           (registry/build-registry)
                instantiated  (pc/instantiate-connectors reg env-conn-types)
                stage-configs (pc/extract-stage-configs env-config)
                res-ctx       (pc/create-resolution-context
                                {:connector-refs (:connector-refs instantiated)
                                 :stage-configs  stage-configs})
                resolved      (pc/resolve-pipeline pipeline res-ctx)]
            (schema/success :pipeline resolved)))))))
