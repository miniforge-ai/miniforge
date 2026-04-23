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

(ns ai.miniforge.workflow.runner-defaults
  "Configuration-as-data for the workflow runner.
   All constants live in config/workflow/runner/defaults.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private defaults
  "Loaded once from classpath resource."
  (delay
    (if-let [r (io/resource "config/workflow/runner/defaults.edn")]
      (:runner/defaults (edn/read-string (slurp r)))
      {})))

(defn max-phases           [] (get @defaults :max-phases 50))
(defn max-redirects        [] (get @defaults :max-redirects 5))
(defn max-backoff-ms       [] (get @defaults :max-backoff-ms 30000))
(defn backoff-base-ms      [] (get @defaults :backoff-base-ms 1000))
(defn pause-poll-interval-ms [] (get @defaults :pause-poll-interval-ms 1000))
(defn default-workdir      [] (get @defaults :default-workdir "/workspace"))
(defn task-branch-prefix   [] (get @defaults :task-branch-prefix "task/"))
(defn default-docker-image [] (get @defaults :default-docker-image "miniforge/task-runner-clojure:latest"))
