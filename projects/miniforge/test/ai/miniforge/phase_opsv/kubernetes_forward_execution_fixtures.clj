;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-forward-execution-fixtures
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.interface :as phase]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} dispositions [{:keys [ctx]}]
  (filterv #(= :opsv.actuation/disposition (:event/type %)) (events/get-events (:event-stream ctx))))

(defn ^{:stratum 0} assembly [{:keys [ctx]}]
  (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                             (get-in ctx [:execution/input :opsv/evidence-bundle-id])))

(defn ^{:stratum 0} deny-policy! [fixture]
  (swap! (:host/context fixture) assoc-in
         [:execution/opts :opsv/governance :policy/context :opsv/allowed-environments] #{}))

(defn ^{:stratum 0} on-claim! [change! armed {:keys [host/runtime request] :as fixture} _calls _options]
  (let [record (effect/read-record (:effects-directory runtime) (:effect/id request))]
    (when (and (= :committing (:effect/state record)) (compare-and-set! armed true false))
      (change! fixture))))

(defn- ^{:stratum 0} context [{:keys [ctx input]}]
  (-> ctx
      (assoc :execution/status :running)
      (assoc-in [:execution/opts :opsv/experiment-execution]
                {:experiment/enabled? true :environment/class :staging :experiment/environment "staging"
                 :experiment/declaration (get-in input [:load :declaration])})))

(defn- ^{:stratum 0} host-runtime [directory authority-directory registered time current]
  (let [effects-directory (str (fs/path directory "native-forward-effects"))
        breach-directory (str (fs/path directory "native-forward-breaches"))
        read-clock (partial deref time)
        read-context (partial deref current)]
    (merge registered {:effects-directory effects-directory
                       :authority-directory authority-directory
                       :breach-directory breach-directory
                       :clock read-clock
                       :current-context read-context})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-runtime [f {:keys [directory ctx] :as fixture}]
  (let [current (atom (context fixture))
        time (atom clock/now)
        supervisor (phase/create-run-supervisor)
        authority-directory (str (fs/path directory "native-forward-authority"))
        registered (phase/register-run-control! supervisor (:execution/id ctx) authority-directory (constantly true))
        runtime (host-runtime directory authority-directory registered time current)]
    (is (some? (:control registered)))
    (f (assoc fixture :host/runtime runtime :host/context current :host/clock time :host/supervisor supervisor))))

(comment
  (fn? with-runtime))
