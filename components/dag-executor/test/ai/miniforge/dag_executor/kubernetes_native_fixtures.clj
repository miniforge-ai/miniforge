;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-native-fixtures
  "Intercept both native command arities; unknown mutations never reach a provider."
  (:require [ai.miniforge.dag-executor.kubernetes-pod-fixtures :as pods]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} dispatch!
  ([state submit! read! until argv] (read! state until argv))
  ([state submit! _read! until argv payload]
   (if (= "create" (second argv))
     (pods/capture-created! state submit! until argv payload)
     (throw (AssertionError.)))))

(comment (dispatch! nil nil (fn [_ _ argv] argv) 0 ["fixture" "get"]))
