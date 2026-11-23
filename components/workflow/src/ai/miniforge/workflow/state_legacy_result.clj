;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-legacy-result
  "Construct the legacy transition-result contract without changing its shape."
  (:require [ai.miniforge.fsm.interface :as fsm]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure [error message]
  {:success? false :error error :message message})

(defn ^{:stratum 0} accepted [machine current-state event]
  (let [snapshot (fsm/transition machine {:_state current-state} event)
        state (fsm/current-state snapshot)]
    {:success? true :state state}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} terminal [state]
  (failure :terminal-state (messages/t :status/terminal-state-transition {:state state})))

(defn ^{:stratum 1} invalid-state [state]
  (failure :invalid-state (messages/t :status/invalid-current-state {:state state})))

(defn ^{:stratum 1} rejected []
  (failure :guard-failed (messages/t :status/guard-rejected-transition)))

(defn ^{:stratum 1} invalid-transition [state event]
  (failure :invalid-transition (messages/t :status/no-transition-defined {:state state :event event})))

(comment ::legacy-result-compatibility)
