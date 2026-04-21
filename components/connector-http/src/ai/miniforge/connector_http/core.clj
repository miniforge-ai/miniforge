(ns ai.miniforge.connector-http.core
  "HttpConnector defrecord — thin protocol wrapper delegating to impl."
  (:require [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-http.impl :as impl]))

(defrecord HttpConnector []
  connector/Connector
  (connect    [_ config auth]                      (impl/do-connect config auth))
  (close      [_ handle]                           (impl/do-close handle))

  connector/SourceConnector
  (discover   [_ handle _opts]                     (impl/do-discover handle))
  (extract    [_ handle _schema-name opts]         (impl/do-extract handle opts))
  (checkpoint [_ _handle _connector-id cursor]     (impl/do-checkpoint cursor)))
