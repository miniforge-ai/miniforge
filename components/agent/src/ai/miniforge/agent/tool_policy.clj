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
(ns ai.miniforge.agent.tool-policy
  "The tool policy as data: reading config/agent/tool-policy.edn and
   checking it against its schema. `tool-supervisor` evaluates tool calls
   against the result."
  (:require
   [clojure.edn :as edn]
   [ai.miniforge.response.interface :as response]
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ToolPolicy
  "Schema for config/agent/tool-policy.edn. Closed, so a misspelt key
   fails the check instead of silently dropping a rule."
  [:map {:closed true}
   [:common [:set :string]]
   [:roles [:map-of :keyword [:set :string]]]
   [:bash/deny-patterns [:vector :string]]])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read-tool-policy
  "Parse tool-policy.edn `text`, check it against `ToolPolicy`, and
   compile its :bash/deny-patterns to regexes. Throws an
   :anomalies/incorrect anomaly when the text does not match; the hook
   then refuses the tool call."
  [text]
  (let [policy (edn/read-string text)]
    (when-not (m/validate ToolPolicy policy)
      (response/throw-anomaly! :anomalies/incorrect
                               "config/agent/tool-policy.edn does not match its schema"
                               {:errors (me/humanize (m/explain ToolPolicy policy))}))
    (update policy :bash/deny-patterns #(mapv re-pattern %))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (read-tool-policy "{:common #{} :roles {:reviewer #{}} :bash/deny-patterns []}")
  ;; => {:common #{} :roles {:reviewer #{}} :bash/deny-patterns []}

  (read-tool-policy "{:common [\"Read\"] :roles {} :bash/deny-patterns []}")
  ;; throws: :common should be a set

  :end)
