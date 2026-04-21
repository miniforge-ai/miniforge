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
   and Apache POI, which aren't BB-compatible."
  (:require
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
;; Output formatting

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
      (do (println "✓ pipeline run complete")
          (println "  run-id:" (:pipeline-run/id run))
          (println "  status:" (:pipeline-run/status run))
          (when-let [stages (:pipeline-run/stage-runs run)]
            (println "  stages:" (count stages))))
      (do (println "✗ pipeline run failed")
          (some->> (:error result) (println "  error:"))))))

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
    (println "  wrote:" out-path)))

(defn- exit! [code] (System/exit (or code 0)))

(defn- get-opts [m] (if (contains? m :opts) (:opts m) m))

;------------------------------------------------------------------------------ Layer 1
;; Subcommand handlers

(defn- run-cmd
  [m]
  (let [{:keys [pipeline env out]} (get-opts m)]
    (cond
      (nil? pipeline) (do (println "usage: etl run <pipeline.edn> --env <env.edn> [--out <result.edn|.json>]") (exit! 1))
      (nil? env)      (do (println "etl run: missing --env <env.edn>") (exit! 1))
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
      (do (println "✗ discovery failed:" (:error result)) (exit! 1))
      (let [pipelines (filter :name (:pipelines result))]
        (println (str (count pipelines) " pipeline(s) discovered under " path ":"))
        (doseq [p pipelines]
          (println "  -" (:path p)
                   (some->> (:name p) (str "  [") (#(str % "]")))
                   (some->> (:version p) (str " v"))))
        (exit! 0)))))

(defn- validate-cmd
  [m]
  (let [{:keys [pipeline env]} (get-opts m)]
    (cond
      (nil? pipeline) (do (println "usage: etl validate <pipeline.edn> --env <env.edn>") (exit! 1))
      (nil? env)      (do (println "etl validate: missing --env <env.edn>") (exit! 1))
      :else
      (let [result (runner/validate-pack pipeline env)]
        (if (schema/succeeded? result)
          (do (println "✓ pack valid — pipeline & env resolve")
              (println "  stages:" (count (:pipeline/stages (:pipeline result))))
              (exit! 0))
          (do (println "✗ validation failed")
              (when-let [e (:error result)] (println "  error:" e))
              (exit! 1)))))))

(defn- help-cmd
  [_m]
  (println "usage: etl {run|list|validate} ...")
  (println)
  (println "  run      <pipeline.edn> --env <env.edn> [--out <path>]")
  (println "  list     [<search-path>]")
  (println "  validate <pipeline.edn> --env <env.edn>")
  (println)
  (println "supported connector types:")
  (doseq [t (registry/supported-types)]
    (println "  -" t))
  (exit! 2))

;------------------------------------------------------------------------------ Layer 2
;; Entry point

(def dispatch-table
  [{:cmds ["run"]      :fn run-cmd      :args->opts [:pipeline]}
   {:cmds ["list"]     :fn list-cmd     :args->opts [:path]}
   {:cmds ["validate"] :fn validate-cmd :args->opts [:pipeline]}
   {:cmds []           :fn help-cmd}])

(defn -main
  [& args]
  (cli/dispatch dispatch-table args))
