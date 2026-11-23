;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-owned-load-creation-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-fixtures :as provider]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-fixtures :as replica]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} native-input [{:keys [ctx input directory] :as fixture}]
  (let [load (:load input)
        runtime (replica/settings)
        authority-directory (str (fs/path directory "owned-load-creation"))
        proposal (phase/prepare-governed-kubernetes-load-proposal! ctx load)]
    (assoc fixture :input load :runtime runtime :authority-directory authority-directory :proposal proposal)))

(defn- ^{:stratum 0} created [native record]
  (let [directory (:authority-directory native)
        provider-settings (:runtime native)
        runtime {:effects-directory directory
                 :provider provider-settings}
        pod (provider/native-response native)]
    {:runtime runtime
     :record record
     :pod pod}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create! [fixture]
  (let [native (native-input fixture)
        [record calls] (provider/commit! native (provider/response native) dispatch/dispatch!)
        stored (effect/read-record (:authority-directory native) (:effect/id record))]
    (is (= :succeeded (:effect/state record)))
    (is (= record stored))
    (is (= 1 (count calls)))
    (created native record)))

(comment
  (fn [retained-fixture] (create! retained-fixture)))
