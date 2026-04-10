(ns ai.miniforge.data-foundry.connector-jira.interface
  "Public API for the Jira Cloud REST API connector component."
  (:require [ai.miniforge.data-foundry.connector-jira.core :as core]
            [ai.miniforge.data-foundry.connector-jira.schema :as schema]))

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
   :connector/retry-policy {:retry/strategy       :exponential-backoff
                            :retry/max-attempts   3
                            :retry/base-delay-ms  1000}
   :connector/maintainer   "data-foundry"})
