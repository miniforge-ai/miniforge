;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-execution-fixtures
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-load-absence-fixtures :as owned]
            [ai.miniforge.phase-opsv.kubernetes-load-start-execution-wire-fixtures :as transport]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} execute transport/execute)

(def ^{:stratum 0} execute-response transport/execute-response)

(defn ^{:stratum 0} mutations [calls] (filterv #(= :patch (:method %)) calls))

(defn ^{:stratum 0} dispositions [{:keys [ctx]}]
  (filterv #(= :opsv.actuation/disposition (:event/type %)) (events/get-events (:event-stream ctx))))

(defn ^{:stratum 0} assembly [{:keys [ctx]}]
  (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                             (get-in ctx [:execution/input :opsv/evidence-bundle-id])))

(defn ^{:stratum 0} pod-reads [fixture calls]
  (count (filter #(and (= :get (:method %)) (= (owned/pod-path fixture) (wire/path %))) calls)))

(defn- ^{:stratum 0} context [{:keys [ctx input]}]
  (-> ctx
      (assoc :execution/status :running)
      (assoc-in [:execution/opts :opsv/experiment-execution]
                {:experiment/enabled? true :environment/class :staging :experiment/environment "staging"
                 :experiment/declaration (get-in input [:load :declaration])})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} configured [f {:keys [directory native pod ctx] :as fixture}]
  (let [current (atom (context fixture))
        time (atom clock/now)
        supervisor (phase/create-run-supervisor)
        authority-directory (str (fs/path directory "native-start-authority"))
        registered (phase/register-run-control! supervisor (:execution/id ctx) authority-directory (constantly true))
        runtime (merge registered {:effects-directory (str (fs/path directory "native-start-effects"))
                                   :authority-directory authority-directory
                                   :breach-directory (str (fs/path directory "native-start-breaches"))
                                   :clock (partial deref time) :current-context (partial deref current)})
        routes (assoc (:routes native) (owned/pod-path fixture) pod)]
    (is (some? (:control registered)))
    (f (assoc fixture :host/runtime runtime :host/context current :host/clock time
              :host/supervisor supervisor :host/routes routes))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-startable [f]
  (owned/with-created (partial configured f)))

(comment
  (with-startable #(count (:host/routes %))))
