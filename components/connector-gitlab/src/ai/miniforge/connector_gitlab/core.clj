(ns ai.miniforge.connector-gitlab.core
  "GitLabConnector defrecord — thin protocol wrapper delegating to impl."
  (:require [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-gitlab.impl :as impl]))

(defrecord GitLabConnector []
  connector/Connector
  (connect    [_ config auth]                      (impl/do-connect config auth))
  (close      [_ handle]                           (impl/do-close handle))

  connector/SourceConnector
  (discover   [_ handle _opts]                     (impl/do-discover handle))
  (extract    [_ handle schema-name opts]          (impl/do-extract handle schema-name opts))
  (checkpoint [_ _handle _connector-id cursor]     (impl/do-checkpoint cursor)))
