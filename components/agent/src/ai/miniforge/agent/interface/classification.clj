(ns ai.miniforge.agent.interface.classification
  "Task-classification helpers for intelligent model selection."
  (:require
   [ai.miniforge.agent.task-classifier :as classifier]))

;------------------------------------------------------------------------------ Layer 0
;; Task classification

(def classify-task classifier/classify-task)
(def get-task-characteristics classifier/get-task-characteristics)
