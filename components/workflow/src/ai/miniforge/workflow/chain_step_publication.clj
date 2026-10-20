;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-step-publication
  "Publish a child terminal outcome, retaining publication failure provenance."
  (:require [ai.miniforge.workflow.chain-outcome :as outcome]
            [ai.miniforge.workflow.chain-publication :as publication]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fail! [opts step index error]
  (publication/emit-step! opts :chain/step-failed step index {:chain/error error}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} complete! [opts step index]
  (let [receipt (publication/emit-step! opts :chain/step-completed step index {})]
    (when (outcome/failure-value? receipt)
      (fail! opts step index (messages/t :chain/step-completion-publication-failed)))
    receipt))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} terminal! [opts step index result]
  (if (outcome/completed? result)
    (complete! opts step index)
    (fail! opts step index (:execution/error result))))

(comment
  ::child-terminal-publication)
