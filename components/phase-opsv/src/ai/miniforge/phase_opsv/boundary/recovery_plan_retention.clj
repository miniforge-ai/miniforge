;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.recovery-plan-retention
  "API surface class 1: actual N4 admission, protected immutable publication and acknowledged references."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifact-confirmation :as confirmation]
            [ai.miniforge.phase-opsv.artifact-model :as artifacts]
            [ai.miniforge.phase-opsv.boundary.recovery-plan-admission :as admission]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.recovery-plan-material :as material]
            [ai.miniforge.phase-opsv.recovery-plan-material-schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refused []
  (anomaly/anomaly :invalid-input (msg/ts :recovery/unretainable-plan) {}))

(defn- ^{:stratum 0} attach-reference [output]
  (assoc output :recovery/artifact-ref (get-in output [:opsv/phase-artifact-ids schema/kind])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} prepared [ctx admitted]
  (let [output {schema/content-key (material/report admitted)}]
    (anomaly/let-ok [value (artifacts/record ctx output schema/material)]
      (let [content (:artifact/content value)]
        (if (and (material/valid-content? content) (material/plan-matches? admitted content)
                 (material/pins-preserved? admitted content))
          {:record value :output (artifacts/begin-publication {schema/content-key content})}
          (refused))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} retain! [ctx input]
  (anomaly/let-ok [admitted (admission/admit! ctx input)
                  publication (prepared ctx admitted)
                  confirmed (confirmation/publish-with-exception-handling ctx (:record publication) (:output publication))]
    (attach-reference confirmed)))

(comment ::denied-admissions-can-be-retained-as-failure-evidence-never-as-approved-plans)
