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
   just wires pack data into live instances.

   JVM-only: transitively loads the concrete connector chain through
   `etl.registry`."
  {:miniforge/runtime :jvm-only}
  (:require
   [ai.miniforge.etl.cursors :as cursors]
   [ai.miniforge.etl.registry :as registry]
   [ai.miniforge.pipeline-config.interface :as pc]
   [ai.miniforge.pipeline-runner.interface :as pr-run]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

;; Env + pipeline assembly
(defn- ^{:stratum 0} load-inputs
  "Load pipeline + env EDNs. On success, returns
   `(schema/success :inputs {:pipeline <edn> :env-config <edn>})`;
   otherwise propagates the first `schema/failure` encountered."
  [pipeline-path env-path]
  (let [pipeline (pc/load-pipeline pipeline-path)]
    (if (schema/failed? pipeline)
      pipeline
      (let [env (pc/load-env-config env-path)]
        (if (schema/failed? env)
          env
          (schema/success :inputs {:pipeline   (:pipeline pipeline)
                                   :env-config (:env-config env)}))))))

(defn- ^{:stratum 0} unsupported-types
  "Connector types referenced by the env that the registry can't
   construct. Returns a vector of keywords."
  [env-conn-types]
  (let [supported (set (registry/supported-types))]
    (->> (vals env-conn-types) distinct (remove supported) vec)))

(defn- ^{:stratum 0} unsupported-types-failure
  "Produce a `schema/failure` describing the unsupported connector
   types, listing what the runner does support for operator guidance."
  [unknown]
  (schema/failure
    :pipeline-run
    (str "Env references connector types not supported by this runner: "
         (pr-str unknown)
         ". Supported: " (pr-str (registry/supported-types)))))

(defn- ^{:stratum 0} instantiate+resolve
  "Given the loaded inputs, instantiate live connectors from the env's
   connector-type declarations and resolve the pipeline EDN against the
   env's stage overrides and the built-in quality rule types. Returns
   `{:resolved ... :connectors ...}`."
  [{:keys [pipeline env-config]}]
  (let [env-conn-types (pc/extract-connector-types env-config)
        registry       (registry/build-registry)
        instantiated   (pc/instantiate-connectors registry env-conn-types)
        stage-configs  (pc/extract-stage-configs env-config)
        rule-registry  (pc/resolve-rules (pc/create-rule-registry))
        res-ctx        (pc/create-resolution-context
                         {:connector-refs (:connector-refs instantiated)
                          :stage-configs  stage-configs
                          :rule-registry  rule-registry})]
    {:resolved   (pc/resolve-pipeline pipeline res-ctx)
     :connectors (:connectors instantiated)}))

(defn- ^{:stratum 0} with-built-in-transforms
  "The runner context with pipeline-runner's built-in transforms registered
   under :transforms. Transforms the caller supplies take precedence."
  [context]
  (update context :transforms #(merge pr-run/built-in-transforms %)))

;; Pack-level helpers (pipeline discovery + validation)
(defn ^{:stratum 0} list-pipelines
  "Discover pipeline EDNs under one or more search paths. Passes through
   to `pipeline-config/discover-pipelines`."
  [search-paths]
  (pc/discover-pipelines search-paths))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} preflighted
  "Return the resolved pipeline as schema/success when pipeline-runner
   preflight accepts it, otherwise preflight's schema/failure."
  [{:keys [resolved connectors]}]
  (let [checked (pr-run/preflight resolved connectors (with-built-in-transforms {}))]
    (if (schema/failed? checked)
      checked
      (schema/success :pipeline resolved))))

;; Public entry
(defn ^{:stratum 1} run-pack
  "Execute the pipeline at `pipeline-path` using the environment at
   `env-path`. `context` is passed through to
   `pipeline-runner/execute-pipeline` (intended for event-stream, log
   sinks, etc.); its `:logger`, if any, also carries the cursor store's
   own logging.

   An `:incremental` pipeline resumes from the watermark its previous
   run persisted and writes a new one when this run succeeds — see
   `etl.cursors` for which modes and outcomes that covers, and note
   that it owns `:pipeline-run/connector-cursors` in the context it
   hands the runner.

   Returns the pipeline-runner result map: schema/success, or
   schema/failure carrying `:pipeline-run` when execution began. A
   failure raised before that — bad inputs, an unsupported connector
   type, an unreadable cursor file — has no run to report and leaves
   `:pipeline-run` nil."
  ([pipeline-path env-path]
   (run-pack pipeline-path env-path {}))
  ([pipeline-path env-path context]
   (let [loaded (load-inputs pipeline-path env-path)]
     (if (schema/failed? loaded)
       loaded
       (let [inputs  (:inputs loaded)
             unknown (unsupported-types (pc/extract-connector-types (:env-config inputs)))]
         (if (seq unknown)
           (unsupported-types-failure unknown)
           (let [{:keys [resolved connectors]} (instantiate+resolve inputs)
                 logger (:logger context)
                 primed (cursors/prime-context logger pipeline-path resolved context)]
             (if (schema/failed? primed)
               primed
               (cursors/persist-cursors!
                logger pipeline-path resolved
                (pr-run/execute-pipeline resolved connectors
                                         (with-built-in-transforms (:context primed))))))))))))

(defn ^{:stratum 1} load-run-configuration
  "Load the concrete pipeline/environment pair that controls one ETL run.

   The returned map intentionally precedes generated stage UUIDs and timestamps,
   which makes it stable enough for factor inventory and baseline/candidate
   comparison. Environment placeholders are resolved exactly as they are for
   execution; the workbench adapter redacts credential-bearing paths before
   any configuration crosses its output boundary."
  [pipeline-path env-path]
  (let [loaded (load-inputs pipeline-path env-path)]
    (if (schema/failed? loaded)
      loaded
      (let [inputs  (:inputs loaded)
            unknown (unsupported-types (pc/extract-connector-types (:env-config inputs)))]
        (if (seq unknown)
          (unsupported-types-failure unknown)
          (schema/success :run-configuration
                          {:pipeline (:pipeline inputs)
                           :environment (:env-config inputs)}))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} validate-pack
  "Load + resolve the pipeline at `pipeline-path` with `env-path` without
   executing it, then preflight it against its connectors and the built-in
   transforms and rules. Surfaces any loader, env, resolver or preflight
   failure."
  [pipeline-path env-path]
  (let [loaded (load-inputs pipeline-path env-path)]
    (if (schema/failed? loaded)
      loaded
      (let [inputs  (:inputs loaded)
            unknown (unsupported-types (pc/extract-connector-types (:env-config inputs)))]
        (if (seq unknown)
          (unsupported-types-failure unknown)
          (preflighted (instantiate+resolve inputs)))))))
