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
(ns ai.miniforge.bb-task-classpath.core
  "Load-check the entry namespaces of bb.edn tasks that carry
   :extra-paths.

   A task's :extra-paths list is a hand-maintained classpath: when an
   entry namespace's require chain grows a component the list doesn't
   cover, the task breaks at namespace load on main with no CI signal
   (third occurrence: PR #1640, after #1626 and the mcp-context-server
   additions). This check reconstructs each such task's classpath in a
   fresh Babashka process and requires its entry namespaces — load
   only, no task execution.

   Layer 0: pure spec extraction + child-program generation
   Layer 1: subprocess execution and reporting"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; ── Layer 0 ──────────────────────────────────────────────────────────
(defn ^{:stratum 0} check-specs
  "One check spec per task declaring both :extra-paths and :requires.
   Root :paths/:deps are merged in because bb puts them on every
   task's classpath. Sorted by task name for stable output."
  [bb-edn]
  (->> (:tasks bb-edn)
       (filter (fn [[task-name task]]
                 (and (symbol? task-name)
                      (map? task)
                      (seq (:extra-paths task))
                      (seq (:requires task)))))
       (map (fn [[task-name task]]
              {:task       task-name
               :paths      (vec (concat (:paths bb-edn) (:extra-paths task)))
               :deps       (merge {} (:deps bb-edn) (:extra-deps task))
               :namespaces (mapv first (:requires task))}))
       (sort-by (comp str :task))
       vec))

(defn ^{:stratum 0} load-program
  "Program for a fresh bb process: reconstruct the task's classpath,
   then require its entry namespaces. The path separator is resolved
   in the child so the program is portable across platforms."
  [{:keys [paths deps namespaces]}]
  (->> [(list 'babashka.deps/add-deps (list 'quote {:deps deps}))
        (list 'babashka.classpath/add-classpath
              (list 'clojure.string/join
                    '(System/getProperty "path.separator")
                    paths))
        (cons 'require (map (fn [n] (list 'quote n)) namespaces))]
       (map pr-str)
       (str/join " ")))

;------------------------------------------------------------------------------ Layer 1

;; ── Layer 1 ──────────────────────────────────────────────────────────
(defn- ^{:stratum 1} check-task!
  [repo-root spec]
  (let [{:keys [exit out err]} (shell/with-sh-dir repo-root
                                 (shell/sh "bb" "-e" (load-program spec)))]
    (assoc spec :exit exit :out out :err err)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check-all!
  "Load-check every bb.edn task with :extra-paths. Prints one line per
   task (plus captured output on failure) and returns a process exit
   code: 0 when every entry namespace loads, 1 otherwise."
  [{:keys [repo-root bb-edn-path]
    :or   {repo-root "." bb-edn-path "bb.edn"}}]
  (let [bb-edn  (edn/read-string (slurp (io/file repo-root bb-edn-path)))
        results (mapv (partial check-task! repo-root) (check-specs bb-edn))]
    (doseq [{:keys [task exit out err]} results]
      (if (zero? exit)
        (println "✓" task)
        (do (println "✗" task "— entry namespaces failed to load (exit" (str exit ")"))
            (doseq [s [out err] :when (not (str/blank? s))]
              (println s)))))
    (if (every? (comp zero? :exit) results) 0 1)))
