;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.native-verification-evidence-fixtures
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as verification]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [ai.miniforge.phase-opsv.kubernetes-controller-configuration-fixtures :as controllers]
            [ai.miniforge.phase-opsv.native-material-fixtures :as native]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} native-observation [fingerprint value]
  (let [capture (controllers/outside-capture (controllers/input))
        captured (assoc-in capture [:kubernetes/inventory :kubernetes/pod-inventory "items" 0
                                    "spec" "containers" 0 "env"] (native/literal-environment value))]
    (assoc (verification/observations fingerprint)
           :environment/raw-observations {:kubernetes/verification captured})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} reports [ctx directory baseline value]
  (let [calls (atom [])
        prepared (native-observation baseline value)
        measured (native-observation (merge baseline (environment/changes)) value)
        result (verification/verify ctx (partial verification/prepare calls prepared)
                 (partial verification/measure calls directory measured))
        retained (get-in @calls [1 2])
        final (artifact/read-published directory
                (get-in result [:opsv/phase-artifact-ids :verification-environment]))]
    {:result result :retained retained :final final :stages (mapv first @calls)}))
