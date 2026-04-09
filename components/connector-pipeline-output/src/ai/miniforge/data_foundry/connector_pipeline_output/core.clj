(ns ai.miniforge.data-foundry.connector-pipeline-output.core
  "PipelineOutputConnector defrecord — thin protocol wrapper delegating to impl."
  (:require [ai.miniforge.data-foundry.connector.protocol :as proto]
            [ai.miniforge.data-foundry.connector-pipeline-output.impl :as impl]))

(defrecord PipelineOutputConnector []
  proto/Connector
  (connect    [_ config _auth]                     (impl/do-connect config))
  (close      [_ handle]                           (impl/do-close handle))

  proto/SinkConnector
  (publish    [_ handle _schema-name records opts] (impl/do-publish handle records opts)))
