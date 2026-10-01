;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.control-authorization
  "Pure RBAC decisions for structured control actions."
  (:require [ai.miniforge.event-stream.messages :as messages]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private target-categories
  {:workflow :workflows :agent :agents :fleet :fleet})

(defn- ^{:stratum 0} granted []
  {:authorized? true :reason (messages/t :control/permitted)})

(defn- ^{:stratum 0} denied [category message context]
  (let [anomaly (response/make-anomaly category message context)]
    {:authorized? false :reason message :anomaly anomaly}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} authorize-action
  "Decide whether the requester's role permits this action on its target."
  [roles action requester]
  (let [role (:role requester)
        role-perms (get roles role)
        target-type (get-in action [:action/target :target-type])
        category (target-categories target-type)
        action-type (:action/type action)
        permitted-actions (get role-perms category #{})
        context {:role role :action-type action-type :target-type target-type}]
    (cond
      (nil? role-perms)
      (denied :anomalies/not-found (messages/t :control/unknown-role context) {:role role})

      (nil? category)
      (denied :anomalies/incorrect (messages/t :control/unknown-target context) {:target-type target-type})

      (contains? permitted-actions action-type)
      (granted)

      :else
      (denied :anomalies/forbidden (messages/t :control/not-permitted context) context))))
