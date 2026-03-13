(ns ai.miniforge.workflow-financial-etl.interface
  "Public API for ETL product-owned execution helpers."
  (:require
   [ai.miniforge.workflow-financial-etl.core :as core]
   [ai.miniforge.workflow-financial-etl.events :as events]))

(def create-etl-state core/create-etl-state)
(def classify-sources core/classify-sources)
(def scan-sources core/scan-sources)
(def extract-knowledge core/extract-knowledge)
(def validate-packs core/validate-packs)
(def run-etl-workflow core/run-etl-workflow)

(def etl-completed-event events/etl-completed-event)
(def etl-failed-event events/etl-failed-event)
(def emit-etl-completed events/emit-etl-completed)
(def emit-etl-failed events/emit-etl-failed)
