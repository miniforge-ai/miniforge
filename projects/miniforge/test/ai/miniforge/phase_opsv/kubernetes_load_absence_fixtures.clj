;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-absence-fixtures
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-fixtures :as owned]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} with-created owned/with-created)

(defn ^{:stratum 0} pod-path [fixture]
  (str (get-in fixture [:record :effect/proposal :kubernetes/request :path]) "/"
       (get-in fixture [:pod "metadata" "name"])))

(defn ^{:stratum 0} observe-with [{:keys [ctx request]} respond!]
  (let [calls (atom [])]
    (with-redefs [http/start! (partial respond! calls) http/response-fields identity]
      [(phase/observe-kubernetes-load-absence! ctx (dissoc request :effect/id)) @calls])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe [fixture routes]
  (observe-with fixture (partial wire/reply! routes)))

(comment
  (pod-path {}))
