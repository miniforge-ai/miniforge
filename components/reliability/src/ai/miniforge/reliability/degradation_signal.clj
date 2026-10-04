;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.reliability.degradation-signal
  "Select the strongest recommendation without performing transitions."
  (:require [ai.miniforge.reliability.budget :as budget]
            [ai.miniforge.reliability.degradation-config :as config]
            [ai.miniforge.reliability.dependency-signal :as dependency]
            [ai.miniforge.reliability.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private mode-rank {:nominal 0 :degraded 1 :safe-mode 2})

(defn- ^{:stratum 0} exhausted-signal []
  (let [message (messages/t :degradation/critical-budget-exhausted)]
    (config/signal :safe-mode :emergency-stop message
                   {:safe-mode-trigger :error-budget :safe-mode-details message})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} budget-signal [budgets]
  (cond
    (budget/critical-budget-exhausted? budgets)
    (exhausted-signal)
    (budget/critical-budget-low? budgets)
    (config/signal :degraded :budget-critical (messages/t :degradation/critical-budget-low))))

(defn- ^{:stratum 1} stronger [left right]
  (cond
    (nil? left) right
    (nil? right) left
    (>= (mode-rank (:mode right)) (mode-rank (:mode left))) right
    :else left))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} recommendation
  ([budgets] (recommendation budgets {} config/defaults))
  ([budgets dependencies] (recommendation budgets dependencies config/defaults))
  ([budgets dependencies policy]
   (or (stronger (budget-signal budgets) (dependency/signal dependencies policy))
       (config/signal :nominal nil (messages/t :degradation/dependency-recovered)))))
