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

(ns etl
  "Babashka ETL task helpers.

   Implements build-time data compilation tasks that require the full JVM
   classpath. Tasks shell out to `clojure -M:poly` so all project components
   are available. New ETL tasks follow the pattern:
     1. Define an eval string that does the work via `build-eval-str`
     2. Call `run-clojure-eval!` to execute it on the full classpath"
  (:require [babashka.process :as p]))

;------------------------------------------------------------------------------ Internal helpers

(defn- run-clojure-eval!
  "Run an expression string via `clojure -M:poly -e EXPR`.
   Inherits stdout/stderr. Calls System/exit on non-zero exit."
  [expr-str]
  (let [{:keys [exit]} (deref
                        (p/process {:out :inherit :err :inherit}
                                   "clojure" "-M:poly" "-e" expr-str))]
    (when-not (zero? exit)
      (System/exit exit))))

(defn- build-standards-pack-eval
  "Build the Clojure eval string for standards pack compilation.

   Uses `pr-str` for the path strings so they are quoted correctly
   inside the eval'd expression, avoiding manual escaping.

   Arguments:
   - standards-dir - Source directory containing .mdc files
   - output-path   - Destination .pack.edn file path

   Returns: String of valid Clojure code ready for `-e`."
  [standards-dir output-path]
  (str
   "(do"
   " (require '[ai.miniforge.policy-pack.mdc-compiler :as c])"
   " (require '[ai.miniforge.policy-pack.loader :as l])"
   " (let [out " (pr-str output-path)
   "       res (c/compile-standards-pack " (pr-str standards-dir) ")]"
   "   (if (:success? res)"
   "     (let [wr (l/write-pack-to-file (:pack res) out)]"
   "       (if (:success? wr)"
   "         (println (str \"✅ \" out \" (\" (count (:pack/rules (:pack res))) \" rules)\"))"
   "         (do (println \"❌ write-pack-to-file failed:\" (:error wr)) (System/exit 1))))"
   "     (do (println \"❌ compile-standards-pack failed:\" (:error res)) (System/exit 1)))))"))

;------------------------------------------------------------------------------ Public ETL tasks

(defn compile-standards-pack!
  "ETL task: Compile .standards/*.mdc files into miniforge-standards.pack.edn.

   Reads all .mdc files under `.standards/`, compiles each to a policy-pack
   Rule map (via mdc-compiler), assembles a complete PackManifest, and writes
   it to `components/phase/resources/packs/miniforge-standards.pack.edn`.

   The output pack is loaded at runtime by `agent-behavior/load-standards-rules`
   and injected into agent prompts for rules with :rule/always-inject? true.

   Runs on the full project classpath (clojure -M:poly) so all policy-pack
   component code is available without extra setup.

   Run via: `bb standards:pack`"
  []
  (let [standards-dir ".standards"
        output-path   "components/phase/resources/packs/miniforge-standards.pack.edn"
        eval-str      (build-standards-pack-eval standards-dir output-path)]
    (println "📦 Compiling" standards-dir "→" output-path "...")
    (run-clojure-eval! eval-str)))
