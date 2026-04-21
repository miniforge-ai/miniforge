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

(ns ai.miniforge.etl.registry
  "Maps pack-declared `:connector/type` keywords to the factory that
   constructs a live connector instance. Only the type→factory mapping
   is hardcoded; everything else (which type a pack uses, which auth
   credentials, which output destination) stays in the pack's env EDN."
  (:require
   [ai.miniforge.connector-edgar.interface :as edgar]
   [ai.miniforge.connector-excel.interface :as excel]
   [ai.miniforge.connector-file.interface :as file]
   [ai.miniforge.connector-github.interface :as github]
   [ai.miniforge.connector-gitlab.interface :as gitlab]
   [ai.miniforge.connector-http.interface :as http]
   [ai.miniforge.connector-jira.interface :as jira]
   [ai.miniforge.connector-pipeline-output.interface :as pipeline-output]
   [ai.miniforge.connector-sarif.interface :as sarif]
   [ai.miniforge.pipeline-config.interface :as pc]))

(def ^:private factories
  {:edgar           edgar/create-edgar-connector
   :excel           excel/create-excel-connector
   :file            file/create-file-connector
   :github          github/create-github-connector
   :gitlab          gitlab/create-gitlab-connector
   :http            http/create-http-connector
   :jira            jira/create-jira-connector
   :pipeline-output pipeline-output/create-pipeline-output-connector
   :sarif           sarif/create-sarif-connector})

(def ^:private metadata
  {:edgar           edgar/connector-metadata
   :excel           excel/connector-metadata
   :file            file/connector-metadata
   :github          github/connector-metadata
   :gitlab          gitlab/connector-metadata
   :http            http/connector-metadata
   :jira            jira/connector-metadata
   :pipeline-output pipeline-output/connector-metadata
   :sarif           sarif/connector-metadata})

(defn supported-types
  "Sorted vector of every connector `:connector/type` keyword the runner
   knows how to instantiate."
  []
  (vec (sort (keys factories))))

(defn build-registry
  "Construct a pipeline-config connector registry with every supported
   type pre-registered. Returns the registry atom."
  []
  (let [registry (pc/create-connector-registry)]
    (doseq [[type-kw factory] factories]
      (pc/register-connector! registry type-kw factory (get metadata type-kw)))
    registry))
