(ns ai.miniforge.data-foundry.connector-excel.interface
  "Public API for the Excel connector component."
  (:require [ai.miniforge.data-foundry.connector-excel.core :as core]))

(defn create-excel-connector
  "Create a new ExcelConnector instance."
  []
  (core/->ExcelConnector))

(def connector-metadata
  "Registration metadata for the Excel connector."
  {:connector/name         "Excel File Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/batch}
   :connector/auth-methods #{:none}
   :connector/maintainer   "data-foundry"})
