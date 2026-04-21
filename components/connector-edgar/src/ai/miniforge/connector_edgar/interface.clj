(ns ai.miniforge.connector-edgar.interface
  "Public API for the EDGAR connector component.

   The EdgarConnector record depends on hato, which isn't
   Babashka-compatible — it's resolved lazily so this interface
   stays loadable under BB.")

(defn create-edgar-connector
  "Create a new EdgarConnector instance."
  []
  (@(requiring-resolve 'ai.miniforge.connector-edgar.core/->EdgarConnector)))

(def connector-metadata
  "Registration metadata for the EDGAR connector."
  {:connector/name         "SEC EDGAR Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/batch :cap/rate-limiting}
   :connector/auth-methods #{:none}
   :connector/maintainer   "data-foundry"})
