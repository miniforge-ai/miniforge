;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.workflow-entry-events
  "Workflow and phase entry facts over current drafts or legacy envelopes."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.core :as legacy]
            [ai.miniforge.event-stream.current-publication :as current]
            [ai.miniforge.event-stream.messages :as msg]
            [ai.miniforge.event-stream.workflow-envelope :as envelope]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} entry-draft [stream event-type workflow-id message payload]
  (let [draft (envelope/create stream event-type workflow-id message {})]
    (if (anomaly/any-anomaly? draft) draft (merge payload draft))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} current-workflow-started [stream workflow-id spec opts]
  (let [payload (assoc (select-keys opts [:workflow/intent :routing/trigger-event-id])
                       :workflow/spec spec)]
    (entry-draft stream :workflow/started workflow-id (msg/t :workflow/started) payload)))

(defn ^{:stratum 1} phase-started
  "The fifth argument supplies :phase/expected-agent for current drafts.
   Intentional agentless phases use :agent/none; absence is never inferred."
  [stream workflow-id phase & [context opts]]
  (if (current/active? stream)
    (entry-draft stream :workflow/phase-started workflow-id
                 (msg/t :workflow/phase-started {:phase (name phase)})
                 {:workflow/phase phase
                  :phase/context context
                  :phase/expected-agent (:phase/expected-agent opts)})
    (legacy/phase-started stream workflow-id phase context)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} workflow-started
  "The options map supplies :workflow/intent for current drafts.
   Construction does not admit or persist the event."
  ([stream workflow-id] (workflow-started stream workflow-id nil nil))
  ([stream workflow-id spec] (workflow-started stream workflow-id spec nil))
  ([stream workflow-id spec opts]
   (if (current/active? stream)
     (current-workflow-started stream workflow-id spec opts)
     (legacy/workflow-started stream workflow-id spec opts))))

(comment
  ::workflow-entry-facts)
