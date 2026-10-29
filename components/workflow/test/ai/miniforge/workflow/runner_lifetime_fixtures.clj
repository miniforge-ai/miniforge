;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-lifetime-fixtures
  (:require [ai.miniforge.workflow.phase-test-support :as phases]
            [ai.miniforge.workflow.runner :as runner]
            [ai.miniforge.workflow.runner-cleanup :as cleanup]
            [ai.miniforge.workflow.runner-environment :as environment]
            [ai.miniforge.workflow.runner-events :as events]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} fail! [failure & _] (throw failure))

(defn- ^{:stratum 0} workflow []
  {:workflow/id :cleanup-lifetime :workflow/version "1.0.0"
   :workflow/pipeline [{:phase phases/runner-test-plan}]})

(defn- ^{:stratum 0} prepare! [failure original definition]
  (if failure (throw failure) (original definition)))

(defn- ^{:stratum 0} stop-error! [failure once?]
  (if once? (first (swap-vals! failure (constantly nil))) @failure))

(defn- ^{:stratum 0} release! [calls captured failure _opts ctx _workflow exception _acquired]
  (swap! calls conj :cleanup)
  (reset! captured {:output ctx :exception exception})
  (when failure (throw failure)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stop! [calls failure once? _handle]
  (swap! calls conj :stop)
  (when-let [error (stop-error! failure once?)] (throw error)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute [failures]
  (let [calls (atom []) captured (atom nil) original runner/build-pipeline
        stop-failure (atom (:stop failures))]
    (with-redefs [environment/acquire-execution-environment! (constantly nil)
                  environment/persist-workspace-at-phase-boundary! (constantly nil)
                  runner/build-pipeline (partial prepare! (:prepare failures) original)
                  events/start-phase-heartbeat! (constantly :owned)
                  events/stop-phase-heartbeat! (partial stop! calls stop-failure (:stop-once? failures))
                  cleanup/post-workflow-cleanup! (partial release! calls captured (:cleanup failures))]
      (let [result (try+ (runner/run-pipeline (workflow) {} {:skip-lifecycle-events true})
                     (catch Object _ (:throwable &throw-context)))]
        {:result result :calls @calls :cleanup @captured}))))

(comment ::public-runner-lifetime-fixtures)
