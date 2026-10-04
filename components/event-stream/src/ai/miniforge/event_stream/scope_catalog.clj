;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.scope-catalog
  "N3 family membership and scope-key vocabulary.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:private true :stratum 0} scope-fields
  {:workflow :workflow/id
   :pr :pr/id
   :pack :pack/id
   :repo :repo/id
   :supervisory-entity :supervisory/entity-key
   :chain :chain/run-id
   :deployment :deployment/id})

(def ^{:private true :stratum 0} inherited-types
  #{:listener/attached :listener/detached :listener/overflow :annotation/created
    :control-action/requested :control-action/executed :control-action/approval-required})

(def ^{:private true :stratum 0} scope-families
  {:pack #{:pack/installed :pack/updated :pack/removed}
   :pr #{:provider/event-received :pr.readiness/changed :pr.risk/changed
         :pr.policy/changed :pr.state/changed :train/changed}
   :deployment #{:reliability/sli-computed :reliability/slo-breach
                 :reliability/error-budget-update :reliability/degradation-mode-changed}
   :repo #{:repo-index/quality-computed :repo-index/canary-failed}
   :chain #{:chain/started :chain/completed :chain/failed
            :chain/step-started :chain/step-completed :chain/step-failed
            :chain.edge/started :chain.edge/completed :chain.edge/failed}
   :supervisory-entity #{:supervisory/workflow-upserted :supervisory/agent-upserted
                         :supervisory/pr-upserted :supervisory/policy-evaluated
                         :supervisory/attention-derived :supervisory/intervention-upserted
                         :supervisory/spec-upserted :supervisory/intervention-requested
                         :supervisory/intervention-state-changed
                         :supervisory/evidence-upserted :supervisory/artifact-upserted
                         :supervisory/task-node-upserted :supervisory/decision-upserted
                         :supervisory/pack-manifest-upserted :supervisory/automation-edge-upserted}})

(defn- ^{:stratum 0} family-entries [[scope types]]
  (map #(vector % scope) types))

(defn- ^{:stratum 0} fallback-scope-type [event-type]
  ;; N3 sections 3.12.1 and 3.19/3.22 enumerate these non-workflow families.
  (when-not (contains? #{"supervisory" "chain" "chain.edge"} (namespace event-type))
    :workflow))

;------------------------------------------------------------------------------ Layer 1

(def ^{:private true :stratum 1} fixed-scopes
  (into {} (mapcat family-entries scope-families)))

(defn ^{:stratum 1} inherited? [event-type]
  (contains? inherited-types event-type))

(defn ^{:stratum 1} field [scope-type]
  (get scope-fields scope-type))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} type-for [event]
  (let [event-type (:event/type event)]
    (if (inherited? event-type)
      (:scope/type event)
      (get fixed-scopes event-type (fallback-scope-type event-type)))))

(comment
  (type-for {:event/type :chain/started}))
