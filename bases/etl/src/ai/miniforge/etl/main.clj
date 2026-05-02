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

   Stratification (intra-namespace):
   Layer 0 — helpers with no in-namespace deps. Mix of pure and
             side-effecting (`stringify-instants` is pure;
             `print-run-summary!` and `exit!` write to stdout / exit).
   Layer 1 — `emit-result!` (composes `stringify-instants`).
   Layer 2 — subcommand handlers (compose Layer 1).
   Layer 3 — `-main` (inlines the dispatch table to keep this file at
             4 strata)."
  {:miniforge/runtime :jvm-only}
  (:require
   [ai.miniforge.etl.messages :as msg]
   [ai.miniforge.etl.registry :as registry]
   [ai.miniforge.etl.runner :as runner]
   [ai.miniforge.schema.interface :as schema]
   [babashka.cli :as cli]
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [clojure.walk :as walk])
  (:import [java.time Instant])
  (:gen-class))

;------------------------------------------------------------------------------ Layer 0
;; No in-namespace dependencies.

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

(defn- exit! [code] (System/exit (or code 0)))

(defn- get-opts [m] (if (contains? m :opts) (:opts m) m))

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
  (let [{:keys [pipeline env out]} (get-opts m)]
    (cond
      (nil? pipeline) (do (println (msg/t :run/usage)) (exit! 1))
      (nil? env)      (do (println (msg/t :run/missing-env)) (exit! 1))
      :else
      (let [result (runner/run-pack pipeline env {})]
        (print-run-summary! result)
        (when out (emit-result! result out))
        (exit! (if (schema/succeeded? result) 0 1))))))

(defn- list-cmd
  [m]
  (let [path   (:path (get-opts m) ".")
        result (runner/list-pipelines [path])]
    (if (schema/failed? result)
      (do (println (msg/t :list/discovery-failed {:error (:error result)})) (exit! 1))
      (let [pipelines (filter :name (:pipelines result))]
        (println (msg/t :list/header {:count (count pipelines) :path path}))
        (doseq [p pipelines]
          (println (msg/t :list/entry
                          {:path    (:path p)
                           :name    (or (some->> (:name p)    (#(msg/t :list/entry-name    {:value %}))) "")
                           :version (or (some->> (:version p) (#(msg/t :list/entry-version {:value %}))) "")})))
        (exit! 0)))))

(defn- validate-cmd
  [m]
  (let [{:keys [pipeline env]} (get-opts m)]
    (cond
      (nil? pipeline) (do (println (msg/t :validate/usage)) (exit! 1))
      (nil? env)      (do (println (msg/t :validate/missing-env)) (exit! 1))
      :else
      (let [result (runner/validate-pack pipeline env)]
        (if (schema/succeeded? result)
          (do (println (msg/t :validate/ok))
              (println (msg/t :validate/stages
                              {:value (count (:pipeline/stages (:pipeline result)))}))
              (exit! 0))
          (do (println (msg/t :validate/failed))
              (when-let [e (:error result)] (println (msg/t :validate/error {:value e})))
              (exit! 1)))))))

(defn- help-cmd
  [_m]
  (println (msg/t :help/usage))
  (println)
  (println (msg/t :help/run))
  (println (msg/t :help/list))
  (println (msg/t :help/validate))
  (println)
  (println (msg/t :help/connector-types))
  (doseq [t (registry/supported-types)]
    (println (msg/t :help/connector-entry {:value t})))
  (exit! 2))

;------------------------------------------------------------------------------ Layer 3
;; Entry point — composes Layer 2.

(defn -main
  [& args]
  (cli/dispatch
   [{:cmds ["run"]      :fn run-cmd      :args->opts [:pipeline]}
    {:cmds ["list"]     :fn list-cmd     :args->opts [:path]}
    {:cmds ["validate"] :fn validate-cmd :args->opts [:pipeline]}
    {:cmds []           :fn help-cmd}]
   args))
