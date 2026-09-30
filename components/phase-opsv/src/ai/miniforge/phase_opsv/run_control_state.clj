;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.run-control-state
  "Factory-owned run handles and atomic registration against a shared stop fence."
  (:require [ai.miniforge.opsv-actuation.interface :as actuation])
  (:import [java.util Collections WeakHashMap]))

;------------------------------------------------------------------------------ Layer 0

(defonce ^{:private true :stratum 0} handles (Collections/synchronizedMap (WeakHashMap.)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record [handle]
  (when (= Object (class handle)) (.get handles handle)))

(defn- ^{:stratum 1} handle-for [record]
  (let [handle (Object.)] (.put handles handle record) handle))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create []
  (handle-for {:kind :supervisor
               :state (atom {:fence (actuation/create-mutation-fence) :runs {}})}))

(defn ^{:stratum 2} register! [supervisor workflow-id directory request-abort!]
  (let [state (:state (record supervisor))]
    (locking state
      (when-not (or (:stopped? (actuation/mutation-status (:fence @state)))
                    (contains? (:runs @state) workflow-id))
        (let [run {:kind :run :parent state :workflow-id workflow-id
                   :authority-directory directory :request-abort! request-abort!
                   :cleanup (atom {})
                   :fence (actuation/create-mutation-fence) :grants (atom #{})}]
          (swap! state assoc-in [:runs workflow-id] run)
          (handle-for run))))))

(defn ^{:stratum 2} stop-snapshot! [supervisor]
  (let [state (:state (record supervisor))]
    (locking state
      (actuation/stop-mutations! (:fence @state))
      (vec (vals (:runs @state))))))

(defn ^{:stratum 2} forget! [handle]
  (let [run (record handle) parent (:parent run)]
    (locking parent
      (when (identical? run (get-in @parent [:runs (:workflow-id run)]))
        (swap! parent update :runs dissoc (:workflow-id run))))))
