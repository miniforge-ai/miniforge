;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-runtime-spec
  "Explicit current-chain runtime options; child publication remains separate."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} CurrentOptions
  [:map
   [:chain/event-stream [:fn events/current-event-stream?]]
   [:chain/retention-directory schema/NonBlankString]
   [:event-stream {:optional true} [:maybe [:fn events/legacy-event-stream?]]]])

(comment
  CurrentOptions)
