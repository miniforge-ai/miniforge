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
(ns ai.miniforge.bb-test-runner.coverage-paths
  "Classify merged classpath paths for Cloverage instrumentation. Split
   out of `core` (pre-split) because these helpers have no connection to
   test *discovery* despite living alongside it historically — their
   only caller is `coverage-cmd/coverage-args`, via `classify-coverage-paths`.

   Layer 0: segment predicates (`test-segment?`, `resource-segment?`)
   and path splitting.
   Layer 1: whole-path predicates built on the segment predicates.
   Layer 2: `classify-coverage-paths`, the public entry point."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} path-segments
  [path]
  (str/split path #"/"))

(defn- ^{:stratum 0} test-segment?
  [segment]
  (boolean (re-find #"^test($|-)" segment)))

(defn- ^{:stratum 0} resource-segment?
  [segment]
  (str/includes? segment "resources"))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} test-path?
  [path]
  (some test-segment? (path-segments path)))

(defn- ^{:stratum 1} resource-path?
  [path]
  (some resource-segment? (path-segments path)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} classify-coverage-paths
  "Split merged classpath paths into source and test roots for
   Cloverage. Resource roots are excluded from instrumentation."
  [paths]
  (let [test-paths (->> paths
                        (filter test-path?)
                        vec)
        source-paths (->> paths
                          (remove test-path?)
                          (remove resource-path?)
                          vec)]
    {:source-paths source-paths
     :test-paths test-paths}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (classify-coverage-paths ["components/agent/src" "components/agent/resources" "components/agent/test"])

  :leave-this-here)
