;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.semantic-report
  "Construct compatible semantic reports from shared rule failures."
  (:require [ai.miniforge.evidence-bundle.semantic-rules :as rules]
            [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private ts
  (messages/create-translator "config/evidence-bundle/messages/semantic.edn" :evidence-bundle/semantic))

(defn- ^{:stratum 0} message-params [intent counts kind]
  (let [expected (get-in rules/rules [intent kind])
        actual (get counts kind)
        kind-name (name kind)]
    {:intent intent :kind kind-name :expected expected :actual actual
     :creates (:creates counts) :destroys (:destroys counts)}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} violation [intent counts kind]
  (let [id (get rules/rule-ids kind)
        severity (if (= :updates kind) :high :critical)
        message-key (if (= :balance kind) :migration-balance :resource-count)
        message (ts message-key (message-params intent counts kind))]
    {:violation/rule-id id
     :violation/severity severity
     :violation/message message}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} build [intent counts checked-at]
  (let [failed (rules/failed-rules intent counts)
        violations (mapv (partial violation intent counts) failed)
        passed? (empty? violations)
        behavior (rules/inferred-behavior counts)]
    {:passed? passed?
     :violations violations
     :semantic-validation/declared-intent intent
     :semantic-validation/actual-behavior behavior
     :semantic-validation/resource-creates (:creates counts)
     :semantic-validation/resource-updates (:updates counts)
     :semantic-validation/resource-destroys (:destroys counts)
     :semantic-validation/checked-at checked-at}))

(comment
  (build :import rules/empty-changes #inst "2026-09-30"))
