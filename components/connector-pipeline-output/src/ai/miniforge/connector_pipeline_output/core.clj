(ns ai.miniforge.connector-pipeline-output.core
  "PipelineOutputConnector defrecord — thin protocol wrapper delegating to impl."
  (:require [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-pipeline-output.impl :as impl]))

(defrecord PipelineOutputConnector []
  connector/Connector
  (connect    [_ config _auth]                     (impl/do-connect config))
  (close      [_ handle]                           (impl/do-close handle))

  connector/SinkConnector
  (publish    [_ handle _schema-name records opts] (impl/do-publish handle records opts)))
