(ns ai.miniforge.data-foundry.connector-file.core
  "FileConnector defrecord — thin protocol wrapper delegating to impl."
  (:require [ai.miniforge.data-foundry.connector.protocol :as proto]
            [ai.miniforge.data-foundry.connector-file.impl :as impl]))

(defrecord FileConnector []
  proto/Connector
  (connect    [_ config _auth]                  (impl/do-connect config))
  (close      [_ handle]                        (impl/do-close handle))

  proto/SourceConnector
  (discover   [_ handle _opts]                  (impl/do-discover handle))
  (extract    [_ handle _schema-name opts]      (impl/do-extract handle opts))
  (checkpoint [_ _handle _connector-id cursor]  (impl/do-checkpoint cursor))

  proto/SinkConnector
  (publish    [_ handle _schema-name records opts] (impl/do-publish handle records opts)))
