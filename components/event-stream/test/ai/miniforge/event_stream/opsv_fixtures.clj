;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.opsv-fixtures
  (:require [ai.miniforge.decision-envelope.interface :as decision]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} disposition-payload
  (let [envelope (decision/envelope [] [] {:pins/pack-revision "test" :pins/rule-ids []
                                         :pins/event-watermark 0})]
    {:opsv/governed-effect {:evidence/effect-id (random-uuid)
                            :evidence/grant-id (random-uuid) :evidence/envelope-id (:envelope/id envelope)}
     :opsv/effect-state :unknown-outcome :opsv/effect-observed {} :opsv/effect-failure nil
     :opsv/decision-envelope envelope}))
