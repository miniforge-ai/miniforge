(ns ai.miniforge.data-foundry.connector-file.interface
  "Public API for the file connector component."
  (:require [ai.miniforge.data-foundry.connector-file.core :as core]
            [ai.miniforge.data-foundry.connector.interface :as conn]))

(defn create-file-connector
  "Create a new FileConnector instance."
  []
  (core/->FileConnector))

(def connector-metadata
  "Registration metadata for the file connector."
  {:connector/name         "File Connector"
   :connector/type         :bidirectional
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/batch}
   :connector/auth-methods #{:none}
   :connector/retry-policy (conn/retry-policy :none)
   :connector/maintainer   "data-foundry"})
