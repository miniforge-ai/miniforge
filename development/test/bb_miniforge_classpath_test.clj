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
(ns bb-miniforge-classpath-test
  "`bb miniforge run`, the dogfood launcher, runs the CLI under Babashka on
   the `miniforge` task's hand-kept :extra-paths and :extra-deps. CI never
   launches it, so these tests check that list covers the CLI project and
   that the CLI and every phase-loader namespace load on it."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private bb-config-file
  "Babashka's task file; its `miniforge` task launches the dev CLI."
  "bb.edn")

(def ^{:stratum 0} ^:private project-deps-file
  "The CLI project the `miniforge` task stands in for during development."
  "projects/miniforge/deps.edn")

(def ^{:stratum 0} ^:private phase-namespaces-file
  "The phase loader's namespace list; a run requires every entry at start."
  "components/phase-software-factory/resources/config/phase/namespaces.edn")

(def ^{:stratum 0} ^:private cli-entry-ns
  "The namespace the `miniforge` task requires to start the CLI."
  'ai.miniforge.cli.main)

(def ^{:stratum 0} ^:private brick-dirs
  "Directories of a brick that must be on the classpath when they exist."
  ["src" "resources"])

(def ^{:stratum 0} ^:private project-brick-root
  "A project :local/root that names a brick, capturing its repo-relative dir."
  #"\.\./\.\./((?:components|bases)/[^/]+)")

(def ^{:stratum 0} ^:private load-script
  "Babashka source: adds the deps (EDN) and classpath given as its first two
   arguments, then requires each namespace in its third (EDN)."
  (str "(require '[babashka.deps :as deps] '[babashka.classpath :as cp] '[clojure.edn :as edn])"
       "(let [[deps-edn classpath nses-edn] *command-line-args*]"
       "  (deps/add-deps {:deps (edn/read-string deps-edn)})"
       "  (cp/add-classpath classpath)"
       "  (doseq [n (edn/read-string nses-edn)] (require n)))"))

(defn- ^{:stratum 0} absolute-classpath [paths]
  (str/join java.io.File/pathSeparator (map #(.getAbsolutePath (io/file %)) paths)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} miniforge-task []
  (get-in (edn/read-string (slurp bb-config-file)) [:tasks 'miniforge]))

(defn- ^{:stratum 1} phase-namespaces []
  (:phase/namespaces (edn/read-string (slurp phase-namespaces-file))))

(defn- ^{:stratum 1} project-bricks []
  (let [deps (:deps (edn/read-string (slurp project-deps-file)))]
    (for [dep   (vals deps)
          :let  [[_ brick] (some->> (:local/root dep) (re-matches project-brick-root))]
          :when brick]
      brick)))

(defn- ^{:stratum 1} unlisted-brick-dirs
  [task bricks]
  (let [listed (set (:extra-paths task))]
    (for [brick bricks
          dir   brick-dirs
          :let  [path (str brick "/" dir)]
          :when (and (.isDirectory (io/file path)) (not (listed path)))]
      path)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} miniforge-task-lists-every-project-brick-test
  (let [missing (vec (unlisted-brick-dirs (miniforge-task) (project-bricks)))]
    (is (empty? missing)
        (str "bb.edn `miniforge` :extra-paths is missing dirs of bricks "
             project-deps-file " depends on: " (pr-str missing)))))

(deftest ^{:stratum 2} miniforge-task-loads-cli-and-phases-test
  (let [task      (miniforge-task)
        classpath (absolute-classpath (:extra-paths task))
        nses      (into [cli-entry-ns] (phase-namespaces))
        result    (p/shell {:out :string :err :string :continue true}
                           "bb" "-e" load-script
                           (pr-str (:extra-deps task)) classpath (pr-str nses))]
    (is (zero? (:exit result))
        (str "the CLI or a phase namespace fails to load on the `miniforge` "
             "task's classpath:\n" (:err result)))))
