;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.terminal
  "Fatal publication boundary: preserve failed cleanup facts without swallowing Errors."
  (:require [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.runner-terminal-failure :as failure]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} with-failed-cleanup! [output-ctx-vol publish]
  (try
    (publish)
    (catch Error error
      (let [refusal (response/from-exception error)]
        (vreset! output-ctx-vol (failure/failed-context @output-ctx-vol refusal)))
      (throw error))))
