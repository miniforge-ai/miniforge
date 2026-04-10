(ns ai.miniforge.data-foundry.connector-gitlab.core
  "GitLabConnector defrecord — thin protocol wrapper delegating to impl."
  (:require [ai.miniforge.data-foundry.connector.protocol :as proto]
            [ai.miniforge.data-foundry.connector-gitlab.impl :as impl]))

(defrecord GitLabConnector []
  proto/Connector
  (connect    [_ config auth]                      (impl/do-connect config auth))
  (close      [_ handle]                           (impl/do-close handle))

  proto/SourceConnector
  (discover   [_ handle _opts]                     (impl/do-discover handle))
  (extract    [_ handle schema-name opts]          (impl/do-extract handle schema-name opts))
  (checkpoint [_ _handle _connector-id cursor]     (impl/do-checkpoint cursor)))
