;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.phase-event-test-support
  "Real current journals with scoped ownership for phase event acceptance tests."
  (:require [ai.miniforge.event-stream.interface :as events]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

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
