(ns ai.miniforge.data-foundry.connector-http.core
  "HttpConnector defrecord — thin protocol wrapper delegating to impl."
  (:require [ai.miniforge.data-foundry.connector.protocol :as proto]
            [ai.miniforge.data-foundry.connector-http.impl :as impl]))

(defrecord HttpConnector []
  proto/Connector
  (connect    [_ config auth]                      (impl/do-connect config auth))
  (close      [_ handle]                           (impl/do-close handle))

  proto/SourceConnector
  (discover   [_ handle _opts]                     (impl/do-discover handle))
  (extract    [_ handle _schema-name opts]         (impl/do-extract handle opts))
  (checkpoint [_ _handle _connector-id cursor]     (impl/do-checkpoint cursor)))
