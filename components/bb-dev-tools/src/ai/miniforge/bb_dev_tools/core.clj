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

(ns ai.miniforge.bb-dev-tools.core
  "Repo-local developer tool catalog and runner.

   Layer 0: pure catalog and command-plan helpers
   Layer 1: repo-rooted catalog loading
   Layer 2: command execution and toolset orchestration"
  (:require [ai.miniforge.bb-paths.interface :as paths]
            [ai.miniforge.bb-test-runner.core :as bb-test-runner]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def ^:const default-filename
  "bb-tools.edn")

(def ^:const default-toolset-id
  :toolset/coverage)

(defn read-edn
  "Parse the tool catalog at `path`. Returns an empty catalog if absent."
  [path]
  (if (fs/exists? path)
    (edn/read-string (slurp path))
    {:tools {} :toolsets {}}))

(defn default-path
  "Absolute path to the repo-local tool catalog."
  []
  (str (fs/path (paths/repo-root) default-filename)))

(defn enabled?
  [tool]
  (get tool :tool/enabled true))

(defn tool
  [catalog tool-id]
  (get-in catalog [:tools tool-id]))

(defn toolset
  [catalog toolset-id]
  (get-in catalog [:toolsets toolset-id]))

(defn resolve-toolset-tools
  "Return enabled tool definitions for the requested toolset."
  [catalog toolset-id]
  (let [tool-ids (get-in catalog [:toolsets toolset-id :toolset/tools] [])]
    (->> tool-ids
         (map #(tool catalog %))
         (filter some?)
         (filter enabled?)
         vec)))

(defn- absolute-cwd
  [repo-root cwd]
  (str (fs/path repo-root (or cwd "."))))

(defn- normalize-commands
  [stage]
  (let [commands (get stage :commands [])
        command (get stage :command)]
    (if command
      [command]
      commands)))

(defn- external-stage-plans
  [repo-root tool stage-key]
  (let [stage (get tool (keyword "tool" (name stage-key)))
        cwd (absolute-cwd repo-root (get stage :cwd))]
    (->> (normalize-commands stage)
         (mapv (fn [command]
                 {:tool/id (get tool :tool/id)
                  :stage stage-key
                  :cwd cwd
                  :command command})))))

(defn- cloverage-config
  [tool]
  (get tool :tool/config {}))

(defn- cloverage-install-plan
  [repo-root tool]
  [{:tool/id (get tool :tool/id)
    :stage :install
    :cwd (absolute-cwd repo-root ".")
    :command (vec (concat ["clojure"]
                          (bb-test-runner/coverage-install-args)))}])

(defn- cloverage-run-plan
  [repo-root tool]
  (let [deps-config (bb-test-runner/load-deps-config repo-root)
        command-args (bb-test-runner/coverage-args deps-config (cloverage-config tool))]
    [{:tool/id (get tool :tool/id)
      :stage :run
      :cwd (absolute-cwd repo-root ".")
      :command (vec (concat ["clojure"] command-args))}]))

(defn stage-plans
  "Build command plans for a tool and stage."
  [repo-root tool stage-key]
  (let [adapter (get tool :tool/adapter)]
    (case adapter
      :clojure/cloverage
      (if (= stage-key :install)
        (cloverage-install-plan repo-root tool)
        (cloverage-run-plan repo-root tool))

      :external/command
      (external-stage-plans repo-root tool stage-key)

      [])))

(defn load-catalog
  "Load the whole repo-local tool catalog."
  ([] (load-catalog (default-path)))
  ([path] (read-edn path)))

(defn- execute-plan
  [{:keys [command cwd] :as plan}]
  (shell/with-sh-dir cwd
    (let [{:keys [exit out err]} (apply shell/sh command)]
      (when-not (str/blank? out)
        (println out))
      (when-not (str/blank? err)
        (binding [*out* *err*]
          (println err)))
      (assoc plan :exit exit))))

(defn- run-stage
  [repo-root tool stage-key]
  (let [plans (stage-plans repo-root tool stage-key)]
    (reduce (fn [results plan]
              (let [result (execute-plan plan)
                    exit (get result :exit)]
                (if (zero? exit)
                  (conj results result)
                  (reduced (conj results result)))))
            []
            plans)))

(defn- toolset-results
  [repo-root catalog toolset-id stage-key]
  (let [tools (resolve-toolset-tools catalog toolset-id)]
    (reduce (fn [results tool]
              (let [stage-results (run-stage repo-root tool stage-key)
                    failed? (some #(pos? (get % :exit 0)) stage-results)
                    next-results (into results stage-results)]
                (if failed?
                  (reduced next-results)
                  next-results)))
            []
            tools)))

(defn install-toolset
  "Install or prefetch all tools in a named toolset. Returns the final exit code."
  [{:keys [repo-root catalog-path toolset-id]
    :or {repo-root "."
         toolset-id default-toolset-id}}]
  (let [catalog-file (or catalog-path (str (fs/path repo-root default-filename)))
        catalog (load-catalog catalog-file)
        results (toolset-results repo-root catalog toolset-id :install)]
    (or (some #(when (pos? (get % :exit 0)) (get % :exit)) results)
        0)))

(defn run-toolset
  "Run all tools in a named toolset. Returns the final exit code."
  [{:keys [repo-root catalog-path toolset-id]
    :or {repo-root "."
         toolset-id default-toolset-id}}]
  (let [catalog-file (or catalog-path (str (fs/path repo-root default-filename)))
        catalog (load-catalog catalog-file)
        results (toolset-results repo-root catalog toolset-id :run)]
    (or (some #(when (pos? (get % :exit 0)) (get % :exit)) results)
        0)))
