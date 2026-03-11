(ns ai.miniforge.workflow.interface.registry
  "Workflow registry and schema-adjacent helpers."
  (:require
   [ai.miniforge.workflow.registry :as registry]
   [ai.miniforge.workflow.schemas :as schemas]))

;------------------------------------------------------------------------------ Layer 0
;; Registry and schema helpers

(def register-workflow! registry/register-workflow!)
(def get-workflow registry/get-workflow)
(def list-workflow-ids registry/list-workflow-ids)
(def workflow-exists? registry/workflow-exists?)
(def workflow-characteristics registry/workflow-characteristics)
(def ensure-initialized! registry/ensure-initialized!)
(def valid-recommendation? schemas/valid-recommendation?)
(def explain-recommendation schemas/explain-recommendation)
