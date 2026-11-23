;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-structure-messages
  "Structural validation diagnostics using the existing message catalog."
  (:require [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} diagnostic [error fields message-key arguments]
  (let [message (messages/t message-key arguments)]
    (assoc fields :error error :message message)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} guarded-fail-missing-default-error [state]
  (diagnostic :guarded-fail-missing-default {:state state}
              :structural/guarded-fail-missing-default {:state state}))

(defn ^{:stratum 1} multiple-redirect-actions-error [state indices]
  (let [number (count indices)]
    (diagnostic :multiple-redirect-counting-actions {:state state :entry-indices indices}
                :structural/multiple-redirect-counting-actions {:state state :count number})))

(defn ^{:stratum 1} budget-misordered-error [state redirect-index budget-index]
  (diagnostic :budget-guard-misordered
              {:state state :redirect-index redirect-index :budget-guard-index budget-index}
              :structural/budget-guard-misordered
              {:state state :redirect-index redirect-index :budget-index budget-index}))

(defn ^{:stratum 1} budget-missing-error [state redirect-index]
  (let [fields {:state state :redirect-index redirect-index}]
    (diagnostic :budget-guard-missing fields :structural/budget-guard-missing fields)))

(comment ::structural-diagnostics)
