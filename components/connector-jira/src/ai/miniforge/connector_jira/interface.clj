(ns ai.miniforge.connector-jira.interface
  "Public API for the Jira Cloud REST API connector component."
  (:require [ai.miniforge.connector-jira.core :as core]
            [ai.miniforge.connector-jira.schema :as schema]
            [ai.miniforge.connector.interface :as conn]))

;;------------------------------------------------------------------------------ Layer 0
;; Schemas (exported for consumers)

(def JiraConfig
  "Malli schema for Jira connector configuration."
  schema/JiraConfig)

;;------------------------------------------------------------------------------ Layer 1
;; Factory and metadata

(defn create-jira-connector
  "Create a new JiraConnector instance."
  []
  (core/->JiraConnector))

(def connector-metadata
  "Registration metadata for the Jira Cloud REST API connector."
  {:connector/name         "Jira Cloud REST API Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/discovery :cap/incremental :cap/pagination}
   :connector/auth-methods #{:basic}
   :connector/retry-policy (conn/retry-policy :default)
   :connector/maintainer   "data-foundry"})
