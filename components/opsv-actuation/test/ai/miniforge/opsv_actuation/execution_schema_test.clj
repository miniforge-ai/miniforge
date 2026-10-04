;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.execution-schema-test
  (:require [ai.miniforge.decision-envelope.interface :as envelope]
            [ai.miniforge.opsv-actuation.execution-schema :as schema]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} allowing-envelope-checks-derived-decision-test
  (let [pins {:pins/pack-revision nil :pins/rule-ids [] :pins/event-watermark nil}
        decision (envelope/envelope [] [] pins)
        reason {:reason/code :reason/gate-check-failed :reason/detail "refused"}]
    (is (m/validate schema/AllowingEnvelope decision))
    (is (not (m/validate schema/AllowingEnvelope
                         (assoc decision :envelope/reasons [reason]))))
    (is (not (m/validate schema/AllowingEnvelope
                         (assoc decision :envelope/produced-by :model))))))

(comment
  (m/validate schema/AllowingEnvelope nil))
