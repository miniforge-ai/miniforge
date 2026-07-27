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
(ns ai.miniforge.connector-github.schema
  "Malli schemas for the GitHub connector.

   Layer 0: Enums and base types
   Layer 1: GitHubConfig and ConnectorMetadata schemas
   Layer 2: Validation helpers"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

;; Enums and base types
(def ^{:stratum 0} auth-methods
  [:api-key :oauth2])

(def ^{:stratum 0} capabilities
  [:cap/discovery :cap/incremental :cap/pagination :cap/rate-limiting])

(def ^{:stratum 0} connector-types
  [:source :sink])

;; Config and metadata schemas
(def ^{:stratum 0} GitHubConfig
  "Schema for GitHub connector configuration.
   Requires either :github/org or :github/owner (or both)."
  [:map
   [:github/base-url {:optional true} string?]
   [:github/org {:optional true} [:maybe string?]]
   [:github/owner {:optional true} [:maybe string?]]
   [:github/repo {:optional true} string?]
   [:github/pull-number {:optional true} int?]])

;; Validation helpers
(defn ^{:stratum 0} valid?
  "1-arity: predicate on a validation result map — true iff :valid? is true.
   2-arity: validate value against a Malli schema."
  ([result] (true? (:valid? result)))
  ([schema value] (m/validate schema value)))

(defn ^{:stratum 0} validate
  "Validate value against schema. Returns {:valid? bool :errors map-or-nil}."
  [schema value]
  (if (m/validate schema value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

(defn ^{:stratum 0} explain
  [schema value]
  (when-let [explanation (m/explain schema value)]
    (me/humanize explanation)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} AuthMethod
  (into [:enum] auth-methods))

(def ^{:stratum 1} Capability
  (into [:enum] capabilities))

(def ^{:stratum 1} ConnectorType
  (into [:enum] connector-types))

(defn ^{:stratum 1} invalid?
  "Predicate: did this validation result fail? Complement of valid?/1."
  [result]
  (not (valid? result)))

(defn ^{:stratum 1} validate-config
  "Validate a GitHub connector config map."
  [value]
  (validate GitHubConfig value))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} ConnectorMetadata
  "Schema for connector registration metadata."
  [:map
   [:connector/name string?]
   [:connector/type ConnectorType]
   [:connector/version string?]
   [:connector/capabilities [:set Capability]]
   [:connector/auth-methods [:set AuthMethod]]
   [:connector/retry-policy
    [:map
     [:retry/strategy keyword?]
     [:retry/max-attempts int?]
     [:retry/base-delay-ms int?]]]
   [:connector/maintainer string?]])

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} validate-metadata
  "Validate connector metadata."
  [value]
  (validate ConnectorMetadata value))
