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

(ns ai.miniforge.bb-etl-runner.core
  "Generic pack-ETL runner.

   Invokes a pack's product-specific CLI (e.g. `ai.thesium.etl.cli`)
   from a miniforge checkout, putting the pack's src on the classpath
   via `:local/root`. Each product adapter supplies the configuration
   (which pack, which CLI namespace, which pipeline/env EDN, where to
   write output); the invocation machinery itself lives here.

   Layer 0: pure — deps override string, invocation-argv builder.
   Layer 1: side-effecting — run!, which shells out and inspects
   exit + output file to produce a uniform result map."
  (:require [ai.miniforge.bb-out.interface :as out]
            [ai.miniforge.bb-proc.interface :as proc]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(defn deps-override
  "Build the -Sdeps string that puts `pack-src` on the classpath as the
   `local/pack` coordinate. The pack's own `deps.edn` (at pack-src) is
   what Clojure resolves for transitive deps."
  [pack-src]
  (str "{:deps {local/pack {:local/root \"" pack-src "\"}}}"))

(defn invocation-argv
  "Argv for `clojure` that loads the pack and invokes its CLI's -main.
   Deterministic — useful for test assertions."
  [{:keys [pack-src cli-ns pipeline env output]}]
  ["clojure" "-Sdeps" (deps-override pack-src)
   "-M:dev" "-m" cli-ns
   "run" pipeline "--env" env "--output" output])

;------------------------------------------------------------------------------ Layer 1
;; Side effects

(defn run!
  "Run a pack ETL and return `{:success? bool :output path-or-nil
   :error str-or-nil}`.

   Required opts:
     :miniforge-dir  abs path to the miniforge checkout (clojure cwd)
     :pack-src       abs path to the pack src dir (deps :local/root)
     :cli-ns         pack CLI ns to `-m` (e.g. `ai.thesium.etl.cli`)
     :pipeline       abs path to pipeline EDN
     :env            abs path to env EDN
     :output         abs path the CLI should write
     :log            abs path for combined stdout log
     :label          human label for status output (e.g. `research-lens`)

   Skipped (not fatal) when miniforge-dir is missing — returns
   `{:success? false :error \"miniforge not found\"}`."
  [{:keys [miniforge-dir pack-src cli-ns pipeline env output log label] :as opts}]
  (out/section (str "Running ETL: " label))
  (cond
    (not (fs/exists? miniforge-dir))
    (do (out/warn (str "miniforge not found at " miniforge-dir ", skipping"))
        {:success? false :output nil :error "miniforge not found"})

    (not (fs/exists? pack-src))
    (do (out/warn (str "pack src not found at " pack-src ", skipping"))
        {:success? false :output nil :error "pack src not found"})

    :else
    (let [[cmd & args] (invocation-argv opts)
          result (apply proc/sh
                        {:dir miniforge-dir :out log :err :inherit}
                        cmd args)]
      (if (and (zero? (:exit result)) (fs/exists? output))
        (do (out/ok (str label " ETL produced " output))
            {:success? true :output output :error nil})
        (do (out/warn (str label " ETL did not produce " output))
            {:success? false :output nil
             :error (str "exit=" (:exit result) ", output missing")})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (deps-override "/tmp/pack/src")
  (invocation-argv {:pack-src "/tmp/pack/src"
                    :cli-ns "ai.thesium.etl.cli"
                    :pipeline "/tmp/p.edn"
                    :env "/tmp/e.edn"
                    :output "/tmp/o.json"})

  :leave-this-here)
