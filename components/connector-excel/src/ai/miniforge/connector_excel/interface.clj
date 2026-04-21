(ns ai.miniforge.connector-excel.interface
  "Public API for the Excel connector component.

   The ExcelConnector record depends on Apache POI (JVM-only) —
   it's resolved lazily so this interface stays loadable under BB.")

(defn create-excel-connector
  "Create a new ExcelConnector instance."
  []
  (@(requiring-resolve 'ai.miniforge.connector-excel.core/->ExcelConnector)))

(def connector-metadata
  "Registration metadata for the Excel connector."
  {:connector/name         "Excel File Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/batch}
   :connector/auth-methods #{:none}
   :connector/maintainer   "data-foundry"})
