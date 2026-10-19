;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.phase-event-test-support
  "Real journals and isolated external runner effects for phase event acceptance."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.runner :as runner]
            [ai.miniforge.workflow.runner-cleanup :as cleanup]
            [ai.miniforge.workflow.runner-environment :as environment]
            [ai.miniforge.workflow.runner-events :as publishers]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} run-phase-workflow [stream definition]
  (with-redefs [environment/acquire-execution-environment! (constantly nil)
                environment/persist-workspace-at-phase-boundary! (constantly nil)
                checkpoints/persist-execution-state! identity
                cleanup/post-workflow-cleanup! (constantly nil)
                publishers/start-phase-heartbeat! (constantly nil)]
    (runner/run-pipeline definition {} {:event-stream stream :skip-lifecycle-events true})))

(defn ^{:stratum 0} with-stream [verify]
  (fs/with-temp-dir [directory {:prefix "phase-event-"}]
    (let [options {:journal-directory (str (fs/canonicalize directory))}
          stream (events/create-current-event-stream options)]
      (is (events/current-event-stream? stream))
      (try+ (verify stream options)
        (finally (events/close-current-event-stream! stream))))))

(defn ^{:stratum 0} reopened-events [options]
  (let [stream (events/create-current-event-stream options)]
    (try+ (events/get-events stream)
      (finally (events/close-current-event-stream! stream)))))
