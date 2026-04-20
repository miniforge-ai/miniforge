(ns ai.miniforge.data-foundry.connector-http.interface
  "Public API for the HTTP connector component."
  (:require [ai.miniforge.data-foundry.connector-http.core :as core]
            [ai.miniforge.data-foundry.connector.interface :as conn]))

(defn create-http-connector
  "Create a new HttpConnector instance."
  []
  (core/->HttpConnector))

(def connector-metadata
  "Registration metadata for the HTTP connector."
  {:connector/name         "HTTP REST Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/discovery :cap/incremental :cap/pagination :cap/rate-limiting}
   :connector/auth-methods #{:api-key :basic :none}
   :connector/retry-policy (conn/retry-policy :default)
   :connector/maintainer   "data-foundry"})
