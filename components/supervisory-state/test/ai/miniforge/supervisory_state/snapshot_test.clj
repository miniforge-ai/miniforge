;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.supervisory-state.snapshot-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.supervisory-state.golden-fixtures :as fixtures]
            [ai.miniforge.supervisory-state.schema :as schema]
            [ai.miniforge.supervisory-state.snapshot :as snapshot]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:private true :stratum 0} expected-key
  {:workflow-run :workflow-run/id
   :spec :spec/id
   :agent :agent/id
   :pr (juxt :pr/repo :pr/number)
   :policy-eval :policy-eval/id
   :attention :attention/id
   :task-node :task/id
   :decision :decision/id
   :intervention :intervention/id})

(deftest ^{:stratum 0} snapshot-preserves-failed-envelope-without-attaching-payload
  (let [failure (anomaly/anomaly :unavailable "injected envelope refusal" {})]
    (is (identical? failure (snapshot/attach-entity failure {})))))

(deftest ^{:stratum 0} incomplete-pr-identity-does-not-create-a-synthetic-scope
  (let [stream (events/create-event-stream {:sinks []})]
    (doseq [{:keys [family ctor entity]} fixtures/families :when (= :pr family)
            missing [:pr/repo :pr/number]]
      (is (nil? (:supervisory/entity-key (ctor stream (dissoc entity missing))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} every-emitter-stamps-the-canonical-entity-key
  (let [stream (events/create-event-stream {:sinks []})]
    (doseq [{:keys [family ctor entity]} fixtures/families
            :let [key-fn (get expected-key family)]
            :when key-fn]
      (let [event (ctor stream entity)]
        (is (= (key-fn entity) (:supervisory/entity-key event)) (name family))
        (is (= entity (:supervisory/entity event)))
        (is (= schema/schema-version (:supervisory/schema-version event)))))))

(comment
  ::scoped-snapshots)
