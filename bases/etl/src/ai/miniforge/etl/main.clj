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
   [cheshire.core :as cheshire]
   [clojure.string :as str])
  (:gen-class))

;------------------------------------------------------------------------------ Layer 0
;; Arg parsing (minimal — three subcommands, two flags)

(defn- parse-flags
  "Strip long-flag/value pairs from args. Returns `[positional flags]`
   where `flags` is a keyword-keyed map. Unknown flags are left on
   positional."
  [args]
  (loop [pos []
         flags {}
         remaining args]
    (cond
      (empty? remaining) [pos flags]
      (and (str/starts-with? (first remaining) "--")
           (seq (rest remaining)))
      (recur pos
             (assoc flags (keyword (subs (first remaining) 2)) (second remaining))
             (drop 2 remaining))
      :else
      (recur (conj pos (first remaining)) flags (rest remaining)))))

;------------------------------------------------------------------------------ Layer 1
;; Output formatting

(defn- print-run-summary!
  "Human-readable line summary of a pipeline-runner result."
  [result]
  (let [run (:pipeline-run result)]
    (if (schema/succeeded? result)
      (do (println "✓ pipeline run complete")
          (println "  run-id:" (:pipeline-run/id run))
          (println "  status:" (:pipeline-run/status run))
          (when-let [stages (:pipeline-run/stage-results run)]
            (println "  stages:" (count stages))))
      (do (println "✗ pipeline run failed")
          (some->> (:error result) (println "  error:"))))))

(defn- emit-result-edn!
  "Write the full pipeline-runner result to `--out` if provided. Writes
   JSON when the path ends in `.json`; EDN otherwise."
  [result out-path]
  (let [json? (str/ends-with? out-path ".json")
        body  (if json? (cheshire/generate-string result {:pretty true}) (pr-str result))]
    (spit out-path body)
    (println "  wrote:" out-path)))

;------------------------------------------------------------------------------ Layer 2
;; Subcommand handlers

(defn- run-cmd
  [[pipeline-path & _] {:keys [env out]}]
  (cond
    (nil? pipeline-path)
    (do (println "usage: etl run <pipeline.edn> --env <env.edn> [--out <result.edn|.json>]")
        1)
    (nil? env)
    (do (println "etl run: missing --env <env.edn>")
        1)
    :else
    (let [result (runner/run-pack pipeline-path env {})]
      (print-run-summary! result)
      (when out (emit-result-edn! result out))
      (if (schema/succeeded? result) 0 1))))

(defn- list-cmd
  [paths _flags]
  (let [search (if (seq paths) paths ["."])
        result (runner/list-pipelines search)]
    (if (schema/failed? result)
      (do (println "✗ discovery failed:" (:error result)) 1)
      (let [pipelines (filter :name (:pipelines result))]
        (println (str (count pipelines) " pipeline(s) discovered under "
                      (str/join ", " search) ":"))
        (doseq [p pipelines]
          (println "  -" (:path p)
                   (some->> (:name p) (str "  [") (#(str % "]")))
                   (some->> (:version p) (str " v"))))
        0))))

(defn- validate-cmd
  [[pipeline-path & _] {:keys [env]}]
  (cond
    (nil? pipeline-path)
    (do (println "usage: etl validate <pipeline.edn> --env <env.edn>") 1)
    (nil? env)
    (do (println "etl validate: missing --env <env.edn>") 1)
    :else
    (let [result (runner/validate-pack pipeline-path env)]
      (if (schema/succeeded? result)
        (do (println "✓ pack valid — pipeline & env resolve")
            (println "  stages:" (count (:pipeline/stages (:pipeline result))))
            0)
        (do (println "✗ validation failed")
            (when-let [e (:error result)] (println "  error:" e))
            1)))))

;------------------------------------------------------------------------------ Layer 3
;; Entry point

(def ^:private dispatch
  {"run"      run-cmd
   "list"     list-cmd
   "validate" validate-cmd})

(defn -main
  [& args]
  (let [[subcommand & rest-args] args
        handler (get dispatch subcommand)]
    (if handler
      (let [[positional flags] (parse-flags rest-args)
            exit               (handler positional flags)]
        (System/exit (or exit 0)))
      (do (println "usage: etl {run|list|validate} ...")
          (println "supported connector types:")
          (doseq [t (registry/supported-types)]
            (println "  -" t))
          (System/exit (if (nil? subcommand) 2 1))))))
