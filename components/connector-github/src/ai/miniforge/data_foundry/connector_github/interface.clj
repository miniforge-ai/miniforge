(ns ai.miniforge.data-foundry.connector-github.interface
  "Public API for the GitHub REST API connector component."
  (:require [ai.miniforge.data-foundry.connector-github.core :as core]))

(defn create-github-connector
  "Create a new GitHubConnector instance."
  []
  (core/->GitHubConnector))

(def connector-metadata
  "Registration metadata for the GitHub REST API connector."
  {:connector/name         "GitHub REST API Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/discovery :cap/incremental :cap/pagination :cap/rate-limiting}
   :connector/auth-methods #{:api-key :oauth2}
   :connector/retry-policy {:retry/strategy       :exponential-backoff
                            :retry/max-attempts   3
                            :retry/base-delay-ms  1000}
   :connector/maintainer   "data-foundry"})
