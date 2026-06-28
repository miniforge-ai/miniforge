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

(ns ai.miniforge.connector-gitlab.schema
  "Malli schemas for the GitLab connector."
  (:require [ai.miniforge.response.interface :as response]
            [malli.core :as m]
            [malli.error :as me]))

;;------------------------------------------------------------------------------ Layer 0
;; Schemas

(def GitLabConfig
  "Schema for GitLab connector configuration.
   Requires either :gitlab/project-id or :gitlab/project-path."
  [:map
   [:gitlab/base-url {:optional true} string?]
   [:gitlab/project-id {:optional true} [:maybe [:or int? string?]]]
   [:gitlab/project-path {:optional true} [:maybe string?]]
   [:gitlab/issue-iid {:optional true} int?]])

;;------------------------------------------------------------------------------ Layer 1
;; Validation

(defn validate
  "Validate value against schema."
  [schema value]
  (if (m/validate schema value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

(defn validate!
  "Validate and throw on failure."
  [schema value]
  (when-not (m/validate schema value)
    (response/throw-anomaly! :anomalies/incorrect
                             "GitLab config validation failed"
                             {:errors (me/humanize (m/explain schema value))
                              :config/error :invalid-config
                              :value value}))
  value)

(comment
  ;; (validate GitLabConfig {:gitlab/project-path "mygroup/myrepo"})
  )
