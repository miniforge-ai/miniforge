;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.control-authorization
  "Pure RBAC decisions for structured control actions.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private target-categories
  {:workflow :workflows :agent :agents :fleet :fleet})

(defn- ^{:stratum 0} granted []
  {:authorized? true :message-key :control/permitted :context {}})

(defn- ^{:stratum 0} denied [category message-key context]
  {:authorized? false :category category :message-key message-key :context context})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} decide
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
      (denied :anomalies/not-found :control/unknown-role {:role role})

      (nil? category)
      (denied :anomalies/incorrect :control/unknown-target {:target-type target-type})

      (and (keyword? action-type) (contains? permitted-actions action-type))
      (granted)

      :else
      (denied :anomalies/forbidden :control/not-permitted context))))
