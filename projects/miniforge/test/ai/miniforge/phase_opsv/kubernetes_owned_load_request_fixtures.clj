;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-owned-load-request-fixtures
  (:require [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} respond! [calls pending options]
  (swap! calls conj options)
  pending)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare [constructor {:keys [ctx request]} pod]
  (let [calls (atom [])
        pending (wire/completed (wire/response (json/generate-string pod)))]
    (with-redefs [http/start! (partial respond! calls pending) http/response-fields identity]
      [(constructor ctx request) @calls])))

(comment ::only-http-is-simulated-not-retained-intent-creation-ownership-or-authority)
