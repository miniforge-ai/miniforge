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
(ns opt-in-profile-test
  "No default project carries an opt-in profile brick, directly or through
   any brick's own `:local/root` dependencies."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-projects
  "The projects a default installation is built from."
  ["projects/miniforge" "projects/miniforge-core" "projects/data-foundry"])

(def ^{:stratum 0} opt-in-project
  "The project that carries the development-agent environment profile."
  "projects/agent-environments")

(def ^{:stratum 0} opt-in-bricks
  "Bricks only an opt-in project may contain."
  #{"components/agent-environment" "components/agent-environment-kubernetes" "components/agent-worker"})

(defn- ^{:stratum 0} local-roots
  "Repo-relative directories `dir`'s deps.edn names as `:local/root`."
  [dir]
  (let [file (io/file dir "deps.edn")
        deps (when (.exists file) (:deps (edn/read-string (slurp file))))]
    (for [dep (vals deps)
          :let [root (:local/root dep)]
          :when root]
      (str (.normalize (.toPath (io/file dir root)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} reachable
  "Every brick `project` reaches through `:local/root` dependencies."
  [project]
  (loop [seen #{}
         frontier (local-roots project)]
    (let [[dir & more] frontier]
      (cond
        (nil? dir) seen
        (contains? seen dir) (recur seen more)
        :else (recur (conj seen dir) (concat more (local-roots dir)))))))

(deftest ^{:stratum 1} test-opt-in-project-carries-every-opt-in-brick
  (is (set/subset? opt-in-bricks (set (local-roots opt-in-project)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-default-projects-reach-no-opt-in-brick
  (doseq [project default-projects]
    (testing (str "given " project " → none of the opt-in bricks")
      (is (seq (reachable project)))
      (is (empty? (set/intersection opt-in-bricks (reachable project)))))))
