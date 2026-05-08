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

(ns ai.miniforge.bb-dev-tools.adapters.cloverage
  "Adapter for Cloverage-backed JVM coverage tasks."
  (:require
   [ai.miniforge.bb-dev-tools.adapter-registry :as registry]
   [ai.miniforge.bb-test-runner.interface :as bb-test-runner]
   [babashka.fs :as fs]))

(defn- repo-cwd
  [repo-root]
  (str (fs/path repo-root ".")))

(defn- cloverage-config
  [tool]
  (get tool :tool/config {}))

(defn- install-plan
  [repo-root tool]
  [{:tool/id (get tool :tool/id)
    :stage :install
    :cwd (repo-cwd repo-root)
    :command (vec (concat ["clojure"]
                          (bb-test-runner/coverage-install-args)))}])

(defn- run-plan
  [repo-root tool]
  (let [deps-config (bb-test-runner/load-deps-config repo-root)
        command-args (bb-test-runner/coverage-args deps-config (cloverage-config tool))]
    [{:tool/id (get tool :tool/id)
      :stage :run
      :cwd (repo-cwd repo-root)
      :command (vec (concat ["clojure"] command-args))}]))

(defmethod registry/stage-plans :clojure/cloverage
  [repo-root tool stage-key]
  (case stage-key
    :install (install-plan repo-root tool)
    :run (run-plan repo-root tool)
    []))
