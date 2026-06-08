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

(ns phases
  "BB task wrappers for phase component CLI utilities.
   Thin delegation layer — logic lives in ai.miniforge.phase.*."
  (:require
   [ai.miniforge.bb-proc.interface :as proc]
   [babashka.process :as p]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Internal helpers

(defn- run-stream!
  "Run a subprocess with inherited stdout/stderr. Returns the exit code."
  [& args]
  (let [{:keys [exit]} (deref (apply p/process {:out :inherit :err :inherit} args))]
    exit))

(defn- dev-classpath
  "Resolve the full Clojure :dev alias classpath for the current project.
   Used to provide all component sources and resources to babashka."
  []
  (let [clojure-cmd (proc/clojure-command)
        result      (p/sh clojure-cmd "-A:dev" "-Spath")]
    (str/trim (:out result))))

;------------------------------------------------------------------------------ Layer 1
;; Task implementations

(defn validate
  "CI gate: validate the default phase transition graph (properties 1–6).

   Resolves the full :dev classpath so that ai.miniforge.phase.validate-task
   and its transitive deps (graph, graph_validator, anomaly, messages) are
   all reachable under babashka. Exits with the same code as the bb subprocess."
  []
  (println "🔍 Validating phase transition graph...")
  (let [cp   (dev-classpath)
        expr "(require '[ai.miniforge.phase.validate-task :as vt]) (vt/run)"
        exit (run-stream! "bb" "-cp" cp "-e" expr)]
    (System/exit exit)))

(comment
  ;; Manual invocation from REPL — requires bb on PATH
  (validate)
  :leave-this-here)
