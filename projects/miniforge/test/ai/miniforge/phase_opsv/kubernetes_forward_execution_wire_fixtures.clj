;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-forward-execution-wire-fixtures
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} respond! [fixture calls pending on-read! options]
  (if (= :get (:method options))
    (let [observed (wire/reply! (:host/routes fixture) calls options)]
      (on-read! fixture @calls options)
      observed)
    (do (swap! calls conj options) pending)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute-response [operation fixture pending on-read!]
  (let [calls (atom [])]
    (with-redefs [http/start! (partial respond! fixture calls pending on-read!) http/response-fields identity]
      [(operation (:host/runtime fixture) (:request fixture)) @calls])))

(comment
  (fn? execute-response))
