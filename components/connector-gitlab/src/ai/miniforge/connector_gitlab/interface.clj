(ns ai.miniforge.connector-gitlab.interface
  "Public API for the GitLab REST API connector component."
  (:require [ai.miniforge.connector-gitlab.core :as core]
            [ai.miniforge.connector-gitlab.schema :as schema]
            [ai.miniforge.connector.interface :as conn]))

;;------------------------------------------------------------------------------ Layer 0
;; Schemas (exported for consumers)

(def GitLabConfig
  "Malli schema for GitLab connector configuration."
  schema/GitLabConfig)

;;------------------------------------------------------------------------------ Layer 1
;; Factory and metadata

(defn create-gitlab-connector
  "Create a new GitLabConnector instance."
  []
  (core/->GitLabConnector))

(def connector-metadata
  "Registration metadata for the GitLab REST API connector."
  {:connector/name         "GitLab REST API Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/discovery :cap/incremental :cap/pagination :cap/rate-limiting}
   :connector/auth-methods #{:api-key :oauth2}
   :connector/retry-policy (conn/retry-policy :default)
   :connector/maintainer   "data-foundry"})

(comment
  ;; (create-gitlab-connector)
  )
