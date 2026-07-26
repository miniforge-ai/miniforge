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
(ns ai.miniforge.bb-test-runner.coverage-cmd
  "Pure derivation of the Cloverage command line: the JVM argv that runs
   coverage, and the argv that prefetches the tool dependency. Requires
   `deps-config` (for merging the repo's alias into a runnable classpath)
   and `coverage-paths` (for splitting that classpath into source/test
   roots) rather than folding either back in — see each namespace's own
   docstring for why the chain needs the cut.

   Layer 0: `cloverage-version`, `default-coverage-output` (constants).
   Layer 1: `coverage-install-args` and `build-coverage-sdeps`, each
   built on a Layer 0 constant plus a cross-namespace call.
   Layer 2: `coverage-args`, built on `build-coverage-sdeps`."
  (:require [ai.miniforge.bb-test-runner.coverage-paths :as coverage-paths]
            [ai.miniforge.bb-test-runner.deps-config :as deps-config]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private cloverage-version
  "1.2.4")

(def ^{:stratum 0} ^:private default-coverage-output
  "target/coverage")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} coverage-install-args
  "Build the JVM argv that prefetches the Cloverage tool dependency."
  []
  ["-P"
   "-Sdeps"
   (pr-str {:deps {'cloverage/cloverage {:mvn/version cloverage-version}}})])

(defn ^{:stratum 1} build-coverage-sdeps
  "Build an ad hoc deps map suitable for running Cloverage against the
   repo's test classpath."
  [deps-config-map opts]
  (let [{:keys [paths deps]} (deps-config/merge-deps-config deps-config-map opts)]
    {:paths paths
     :deps (assoc deps
                  'cloverage/cloverage
                  {:mvn/version cloverage-version})}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} coverage-args
  "Build the JVM command argv for a Cloverage run over the repo at the
   given deps config."
  [deps-config-map {:keys [output-dir fail-threshold] :as opts
                     :or {output-dir default-coverage-output
                          fail-threshold 0}}]
  (let [sdeps (build-coverage-sdeps deps-config-map opts)
        {:keys [source-paths test-paths]}
        (coverage-paths/classify-coverage-paths (get sdeps :paths))
        output-path (or output-dir default-coverage-output)
        source-args (mapcat #(vector "--src-ns-path" %) source-paths)
        test-args (mapcat #(vector "--test-ns-path" %) test-paths)]
    (vec (concat ["-Sdeps" (pr-str sdeps)
                  "-M"
                  "-m" "cloverage.coverage"
                  "--output" output-path
                  "--text"
                  "--html"
                  "--summary"
                  "--fail-threshold" (str fail-threshold)]
                 source-args
                 test-args))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (coverage-install-args)
  (coverage-args {:paths ["src" "test"]
                  :deps '{org.clojure/clojure {:mvn/version "1.12.0"}}
                  :aliases {:test {:extra-paths ["test"]}}}
                 {:alias-key :test})

  :leave-this-here)
