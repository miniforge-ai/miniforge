;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.recovery-draft-fixtures
  (:require [ai.miniforge.event-stream.commit-test-support :as envelope]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} payloads []
  (let [phase {:phase/name :plan}
        machine {:machine/state :phase-0-plan}
        failure {:error/message "Disk full" :failure/class :failure.class/resource}
        checkpoint (assoc phase :checkpoint/path "plan.edn" :checkpoint/size-bytes 128
                          :checkpoint/root "checkpoints")
        snapshot (assoc machine :snapshot/path "machine.edn" :snapshot/size-bytes 256
                        :checkpoint/root "checkpoints")
        checkpoint-failure (merge phase failure)
        snapshot-failure (merge machine failure)]
    {:workflow/checkpoint-written checkpoint
     :workflow/checkpoint-write-failed checkpoint-failure
     :workflow/machine-snapshot-written snapshot
     :workflow/machine-snapshot-write-failed snapshot-failure
     :workflow/resumed {:from-state :phase-1-implement :from-phase :implement :skipping [:plan]}
     :workflow/spec-hash-mismatch {:expected-hash "expected" :actual-hash "observed"}}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} draft [workflow-id event-type]
  (merge (envelope/draft) (get (payloads) event-type)
         {:event/type event-type :scope/type :workflow :workflow/id workflow-id}))

(comment ::recovery-wire-fixtures)
