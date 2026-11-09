;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-execution-wire-fixtures
  (:require [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-forward-execution-wire-fixtures :as wire]
            [ai.miniforge.phase-opsv.kubernetes-load-start-provider-fixtures :as native]
            [ai.miniforge.phase-opsv.prometheus-test-support :as response]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} execute-response [fixture pending on-read!]
  (wire/execute-response phase/execute-kubernetes-load-start! fixture pending on-read!))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute
  ([fixture] (execute fixture (constantly nil)))
  ([fixture on-read!]
   (execute-response fixture
                     (response/completed (response/response (json/generate-string (native/response (:pod fixture)))))
                     on-read!)))

(comment
  (native/response {"spec" {"schedulingGates" []}}))
