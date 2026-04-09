(ns ai.miniforge.data-foundry.connector-edgar.interface
  "Public API for the EDGAR connector component."
  (:require [ai.miniforge.data-foundry.connector-edgar.core :as core]))

(defn create-edgar-connector
  "Create a new EdgarConnector instance."
  []
  (core/->EdgarConnector))

(def connector-metadata
  "Registration metadata for the EDGAR connector."
  {:connector/name         "SEC EDGAR Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/batch :cap/rate-limiting}
   :connector/auth-methods #{:none}
   :connector/maintainer   "data-foundry"})
