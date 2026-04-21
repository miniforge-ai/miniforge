(ns ai.miniforge.connector.protocol)

(defprotocol Connector
  "Base connector protocol. All connectors must implement connect and close."
  (connect [this config auth]
    "Establish connection. Returns {:connection/handle str :connector/status keyword}")
  (close [this handle]
    "Terminate connection. Returns {:connector/status :closed}"))

(defprotocol SourceConnector
  "Source connector protocol for data extraction."
  (discover [this handle opts]
    "Discover available schemas. Returns {:schemas [...] :discover/total-count long}")
  (extract [this handle schema-name opts]
    "Extract records. Returns {:records [...] :extract/cursor map :extract/has-more bool}")
  (checkpoint [this handle connector-id cursor-state]
    "Persist cursor state. Returns {:checkpoint/id uuid :checkpoint/status :committed}"))

(defprotocol SinkConnector
  "Sink connector protocol for data publication."
  (publish [this handle schema-name records opts]
    "Write records. Returns {:publish/records-written long :publish/records-failed long}"))
