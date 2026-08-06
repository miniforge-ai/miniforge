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
(ns ai.miniforge.cli.workflow-runner.paths
  "Filesystem path vocabulary for the workflow runner: normalization,
   executable/command-path resolution, and the runtime-path assertions
   that keep a run's source root, worktree, and spec source dir
   aligned. Split out of `ai.miniforge.cli.workflow-runner` (rule 210:
   the parent namespace measured 10 real layers, max 3; each concern
   moves to its own layer-coherent file — same approach as the
   dag-orchestrator split, miniforge#1485)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} valid-source-root?
  [source-root]
  (and source-root
       (fs/exists? source-root)
       (fs/exists? (fs/path source-root ".git"))))

(defn- ^{:stratum 0} normalize-path
  [path]
  (when path
    (let [resolved (-> path
                       fs/path
                       fs/absolutize)]
      (str (if (fs/exists? resolved)
             (fs/canonicalize resolved)
             (.normalize resolved))))))

(defn- ^{:stratum 0} executable-file?
  [path]
  (let [file (some-> path fs/file)]
    (and file
         (fs/exists? file)
         (not (fs/directory? file))
         (fs/executable? file))))

(defn- ^{:stratum 0} direct-command-path?
  [cmd]
  (boolean (re-find #"[\\/]" (or cmd ""))))

(defn- ^{:stratum 0} path-entries
  []
  (str/split (or (System/getenv "PATH") "") #":"))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} normalize-command-path
  [path]
  (when (executable-file? path)
    (str (-> path
             fs/path
             fs/absolutize))))

(defn- ^{:stratum 1} matching-command-path
  [entry cmd]
  (let [candidate (fs/path entry cmd)]
    (when (executable-file? candidate)
      (str candidate))))

(defn- ^{:stratum 1} source-dir-under-root?
  [source-dir source-root]
  (let [source-dir-path (some-> source-dir normalize-path fs/path)
        source-root-path (some-> source-root normalize-path fs/path)]
    (or (nil? source-dir-path)
        (nil? source-root-path)
        (.startsWith source-dir-path source-root-path))))

(defn ^{:stratum 1} assert-valid-source-root!
  [context]
  (let [source-root (:source-root context)]
    (when-not (valid-source-root? source-root)
      (response/throw-anomaly! :anomalies/incorrect
                               (str "Invalid workflow source root: " source-root)
                               {:source-root source-root
                                :worktree-path (:worktree-path context)}))))

(defn ^{:stratum 1} assert-execution-worktree!
  [context]
  (let [expected (normalize-path (get-in context [:execution/opts :worktree-path]))
        actual (normalize-path (:worktree-path context))]
    (when (and expected actual (not= expected actual))
      (response/throw-anomaly! :anomalies/incorrect
                               (str "Execution worktree mismatch: expected "
                                    expected " but runtime is using " actual)
                               {:expected-worktree expected
                                :actual-worktree actual
                                :source-root (:source-root context)}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-cli-command-path
  "Absolute path of `cmd` when it resolves to an executable: a direct
   path is normalized in place; a bare command is looked up via
   `fs/which` and then, as a fallback, by scanning PATH entries."
  [cmd]
  (cond
    (str/blank? cmd) nil
    (direct-command-path? cmd)
    (normalize-command-path cmd)

    :else
    (or (some-> cmd fs/which str normalize-command-path)
        (some #(matching-command-path % cmd) (path-entries)))))

(defn ^{:stratum 2} assert-source-dir-alignment!
  [spec context]
  (let [source-dir (:spec/source-dir spec)
        source-root (:source-root context)]
    (when-not (source-dir-under-root? source-dir source-root)
      (response/throw-anomaly! :anomalies/incorrect
                               (str "Spec source directory is outside the workflow source root: "
                                    source-dir)
                               {:source-dir source-dir
                                :source-root source-root
                                :worktree-path (:worktree-path context)}))))
