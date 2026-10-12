;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-edge-support
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.chain-current-lifecycle-support :as support]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.runner :as runner]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} definition [bindings]
  {:chain/id :binding-edges :chain/version "1.0.0"
   :chain/steps [{:step/id :first :step/workflow-id :producer}
                 {:step/id :second :step/workflow-id :consumer :step/input-bindings bindings}]})

(defn- ^{:stratum 0} execute [calls status _ input opts]
  (swap! calls conj {:id (:workflow-id opts) :input input :opts opts})
  {:execution/id (:workflow-id opts)
   :execution/status (if (= 1 (count @calls)) :completed status)
   :execution/output {:last-phase-result {:flag false :password "fixture-password"}}
   :execution/edges [::forged-child-edge]
   :execution/error "fixture downstream failure"})

(defn ^{:stratum 0} types [stream]
  (if stream (mapv :event/type (events/get-events stream)) []))

(defn ^{:stratum 0} with-fixture [operation]
  (fs/with-temp-dir [root {:prefix "chain-edge-integration-"}]
    (let [retention (support/directory root "retention")
          journal (support/directory root "journal")
          stream (events/create-current-event-stream {:journal-directory journal})
          opts (support/options retention stream)]
      (try+ (operation opts journal stream)
        (finally (events/close-current-event-stream! stream))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-definition [definition opts status calls loads]
  (with-redefs [loader/load-workflow (fn [& _]
                                      (swap! loads conj (types (:chain/event-stream opts)))
                                      {:workflow {}})
                runner/run-pipeline (partial execute calls status)]
    (workflow/run-chain definition {} opts)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-chain [opts bindings status calls loads]
  (run-definition (definition bindings) opts status calls loads))

(comment
  (definition {}))
