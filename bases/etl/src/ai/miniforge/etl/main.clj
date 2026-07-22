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

(ns ai.miniforge.etl.main
  "JVM entry point for `mf etl` subcommands. Shelled out to from the
   Babashka CLI because the concrete source connectors depend on hato
   and Apache POI, which aren't BB-compatible.

   The entry point and subcommand handlers share the top stratum; serialization
   and value-normalization remain below them."
  {:miniforge/runtime :jvm-only}
  (:require
   [ai.miniforge.etl.messages :as msg]
   [ai.miniforge.etl.registry :as registry]
   [ai.miniforge.etl.runner :as runner]
   [ai.miniforge.etl.workbench :as workbench]
   [ai.miniforge.schema.interface :as schema]
   [babashka.cli :as cli]
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.walk :as walk])
  (:import [java.time Instant])
  (:gen-class))

;------------------------------------------------------------------------------ Layer 0
;; No in-namespace dependencies.

(def ^:private successful-exit-code
  "POSIX status returned after a command completes successfully."
  0)

(def ^:private failed-exit-code
  "POSIX status returned when a command cannot complete."
  1)

(def ^:private usage-exit-code
  "POSIX status reserved for help/usage text output."
  2)

(defn- stringify-instants
  "Walk `v` converting every `java.time.Instant` to its ISO-8601 string
   representation. Required because Instants round-trip through neither
   plain `pr-str` (emits `#object[...]`) nor `cheshire/generate-string`
   (throws without an encoder). Same pattern as `cursor-store`."
  [v]
  (walk/postwalk (fn [x] (if (instance? Instant x) (str x) x)) v))

(defn- print-run-summary!
  "Human-readable line summary of a pipeline-runner result."
  [result]
  (let [run (:pipeline-run result)]
    (if (schema/succeeded? result)
      (do (println (msg/t :run/complete))
          (println (msg/t :run/run-id {:value (:pipeline-run/id run)}))
          (println (msg/t :run/status  {:value (:pipeline-run/status run)}))
          (when-let [stages (:pipeline-run/stage-runs run)]
            (println (msg/t :run/stages {:value (count stages)}))))
      (do (println (msg/t :run/failed))
          (when-let [error (:error result)]
            (println (msg/t :run/error {:value error})))))))

(defn- exit! [code] (System/exit (or code successful-exit-code)))

(defn- get-opts [m] (if (contains? m :opts) (:opts m) m))

(defn- projectable-run?
  "Whether a runner result carries enough execution state for a snapshot.
   Stage failures retain :pipeline-run and remain projectable; failures before
   execution begins do not."
  [result]
  (some? (:pipeline-run result)))

;------------------------------------------------------------------------------ Layer 1
;; Composes Layer 0.

(defn- emit-result!
  "Write the pipeline-runner result to `out-path`. Writes JSON when the
   path ends in `.json`; EDN otherwise. Instants are stringified first
   so both formats remain round-trippable."
  [result out-path]
  (let [normalized (stringify-instants result)
        body       (if (str/ends-with? out-path ".json")
                     (cheshire/generate-string normalized {:pretty true})
                     (pr-str normalized))]
    (spit out-path body)
    (println (msg/t :run/wrote {:path out-path}))))

;------------------------------------------------------------------------------ Layer 2
;; Subcommand handlers — compose Layer 1.

(defn- run-cmd
  [m]
  (let [{:keys [pipeline env out workbench-out source-hash]
         :as opts} (get-opts m)
        workbench-option (when workbench-out
                           (workbench/missing-option opts))]
    (cond
      (nil? pipeline) (do (println (msg/t :run/usage)) (exit! failed-exit-code))
      (nil? env)      (do (println (msg/t :run/missing-env)) (exit! failed-exit-code))
      workbench-option
      (do (println (msg/t :workbench/missing-option {:option workbench-option}))
          (println (msg/t :workbench/usage))
          (exit! failed-exit-code))
      (and workbench-out (not (workbench/valid-source-hash? source-hash)))
      (do (println (msg/t :workbench/invalid-source-hash))
          (exit! failed-exit-code))
      :else
      (let [config-result (when workbench-out
                            (runner/load-run-configuration pipeline env))
            result        (if (and config-result (schema/failed? config-result))
                            config-result
                            (runner/run-pack pipeline env {}))
            snapshot-result
            (when workbench-out
              (cond
                (schema/failed? config-result)
                (schema/failure :snapshot (:error config-result))

                ;; Input/setup failures have no execution state to project.
                ;; Stage failures retain :pipeline-run and must emit their
                ;; failure-state evaluations.
                (not (projectable-run? result))
                nil

                :else
                (workbench/project result (:run-configuration config-result) opts)))]
        (print-run-summary! result)
        (when out (emit-result! result out))
        (when snapshot-result
          (if (schema/succeeded? snapshot-result)
            (emit-result! (:snapshot snapshot-result) workbench-out)
            (println (msg/t :workbench/failed {:error (:error snapshot-result)}))))
        (exit! (if (and (schema/succeeded? result)
                        (or (nil? snapshot-result)
                            (schema/succeeded? snapshot-result)))
                 successful-exit-code
                 failed-exit-code))))))

(defn- list-cmd
  [m]
  (let [path   (:path (get-opts m) ".")
        result (runner/list-pipelines [path])]
    (if (schema/failed? result)
      (do (println (msg/t :list/discovery-failed {:error (:error result)}))
          (exit! failed-exit-code))
      (let [pipelines (filter :name (:pipelines result))]
        (println (msg/t :list/header {:count (count pipelines) :path path}))
        (doseq [p pipelines]
          (println (msg/t :list/entry
                          {:path    (:path p)
                           :name    (or (some->> (:name p)    (#(msg/t :list/entry-name    {:value %}))) "")
                           :version (or (some->> (:version p) (#(msg/t :list/entry-version {:value %}))) "")})))
        (exit! successful-exit-code)))))

(defn- validate-cmd
  [m]
  (let [{:keys [pipeline env]} (get-opts m)]
    (cond
      (nil? pipeline) (do (println (msg/t :validate/usage)) (exit! failed-exit-code))
      (nil? env)      (do (println (msg/t :validate/missing-env))
                          (exit! failed-exit-code))
      :else
      (let [result (runner/validate-pack pipeline env)]
        (if (schema/succeeded? result)
          (do (println (msg/t :validate/ok))
              (println (msg/t :validate/stages
                              {:value (count (:pipeline/stages (:pipeline result)))}))
              (exit! successful-exit-code))
          (do (println (msg/t :validate/failed))
              (when-let [e (:error result)] (println (msg/t :validate/error {:value e})))
              (exit! failed-exit-code)))))))

(defn- registry-cmd
  [m]
  (let [{:keys [out]} (get-opts m)]
    (cond
      (nil? out)
      (do (println (msg/t :registry/usage)) (exit! failed-exit-code))

      (not (workbench/valid-registry?))
      (do (println (msg/t :registry/invalid)) (exit! failed-exit-code))

      :else
      (do (emit-result! (workbench/state-var-registry) out)
          (exit! successful-exit-code)))))

(defn- help-cmd
  [_m]
  (println (msg/t :help/usage))
  (println)
  (println (msg/t :help/run))
  (println (msg/t :help/list))
  (println (msg/t :help/validate))
  (println (msg/t :help/registry))
  (println)
  (println (msg/t :help/connector-types))
  (doseq [t (registry/supported-types)]
    (println (msg/t :help/connector-entry {:value t})))
  (exit! usage-exit-code))

(defn -main
  [& args]
  (cli/dispatch
   [{:cmds ["run"]      :fn run-cmd      :args->opts [:pipeline]}
    {:cmds ["list"]     :fn list-cmd     :args->opts [:path]}
    {:cmds ["validate"] :fn validate-cmd :args->opts [:pipeline]}
    {:cmds ["registry"] :fn registry-cmd}
    {:cmds []           :fn help-cmd}]
   args))
