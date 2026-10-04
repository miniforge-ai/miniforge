;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.scope-policy
  "N3 sections 2.3 and 6: event family, not incidental cross-references, owns scope."
  (:require [ai.miniforge.event-stream.commit-model :as model]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:private true :stratum 0} scope-fields
  {:workflow :workflow/id
   :pr :pr/id
   :pack :pack/id
   :repo :repo/id
   :supervisory-entity :supervisory/entity-key
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
   :supervisory-entity #{:supervisory/workflow-upserted :supervisory/agent-upserted
                         :supervisory/pr-upserted :supervisory/policy-evaluated
                         :supervisory/attention-derived :supervisory/intervention-upserted
                         :supervisory/evidence-upserted :supervisory/artifact-upserted
                         :supervisory/task-node-upserted :supervisory/decision-upserted
                         :supervisory/pack-manifest-upserted :supervisory/automation-edge-upserted}})

(defn- ^{:stratum 0} family-entries [[scope types]]
  (map #(vector % scope) types))

(defn- ^{:stratum 0} fallback-scope-type [event-type]
  ;; N3 section 3.19 requires explicit registration of every supervisory member.
  (when-not (= "supervisory" (namespace event-type)) :workflow))

;------------------------------------------------------------------------------ Layer 1

(def ^{:private true :stratum 1} fixed-scopes
  (into {} (mapcat family-entries scope-families)))

(defn ^{:stratum 1} inherited? [event-type]
  (contains? inherited-types event-type))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} scope
  "Resolve one non-nil scope. The publication boundary validates envelope shapes.
   Inherited families must name a supported :scope/type; never infer it from keys."
  [event]
  (let [event-type (:event/type event)
        scope-type (if (inherited? event-type)
                     (:scope/type event)
                     (get fixed-scopes event-type (fallback-scope-type event-type)))
        field (get scope-fields scope-type)
        id (get event field)]
    (if (and field (some? id))
      [scope-type id]
      (model/failure :invalid-input :publication/invalid-scope event))))

(comment
  (scope {:event/type :workflow/started :workflow/id (random-uuid)}))
