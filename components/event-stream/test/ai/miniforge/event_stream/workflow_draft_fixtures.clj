;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.workflow-draft-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.commit-test-support :as envelope]
            [ai.miniforge.event-stream.interface :as events]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} payloads
  {:workflow/started {:workflow/spec {:workflow/id :fixture}
                      :workflow/intent {:intent/description "fixture intent"}}
   :workflow/phase-started {:workflow/phase :implement
                           :phase/expected-agent :implementer
                           :phase/context {}}
   :workflow/phase-completed {:workflow/phase :implement
                             :phase/duration-ms 1
                             :phase/outcome :success
                             :phase/artifacts []}
   :workflow/completed {:workflow/duration-ms 2
                        :workflow/evidence-bundle-id (random-uuid)}
   :workflow/failed {:workflow/failure-reason "fixture failure"
                     :failure/class :failure.class/task-code}
   :workflow/cancelled {:cancel/requested-by "fixture operator"
                        :cancel/source :cli
                        :workflow/duration-ms 2}})

(defn ^{:stratum 0} with-stream [operation]
  (fs/with-temp-dir [directory {:prefix "workflow-current-admission-"}]
    (let [path (str (fs/real-path directory))
          options {:journal-directory path}
          stream (events/create-current-event-stream options)]
      (is (not (anomaly/anomaly? stream)) (pr-str stream))
      (when-not (anomaly/anomaly? stream)
        (try+ (operation stream options)
          (finally (events/close-current-event-stream! stream)))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} draft [workflow-id event-type]
  (merge (envelope/draft) (get payloads event-type)
         {:event/type event-type :scope/type :workflow :workflow/id workflow-id}))

(comment
  (draft (random-uuid) :workflow/started))
