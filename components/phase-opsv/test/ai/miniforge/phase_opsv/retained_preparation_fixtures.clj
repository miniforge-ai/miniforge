;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.retained-preparation-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-model :as model]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capture! [captured operation request]
  (reset! captured request)
  (operation request))

(defn ^{:stratum 0} acknowledge! [ctx id]
  (evidence/accumulate-opsv-evidence! (:opsv/evidence-assembly-store ctx)
    (get-in ctx [:execution/input :opsv/evidence-bundle-id]) {:opsv/artifact-refs [id]}))

(defn ^{:stratum 0} record
  ([ctx content] (record ctx content :verification-environment :opsv-verification-report))
  ([ctx content kind type]
   (model/record ctx {:opsv/verification-environment content} [kind type :opsv/verification-environment])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish! [ctx value]
  (let [published (artifact/publish! (get-in ctx [:execution/opts :opsv/artifact-directory]) value)
        id (:artifact/id published)]
    (is (not (anomaly/anomaly? published)))
    (is (contains? (:opsv/artifact-refs (acknowledge! ctx id)) id))
    id))

(defn- ^{:stratum 1} with-context [f ctx directory baseline]
  (let [captured (atom nil)
        calls (atom [])
        prepare (partial fixtures/prepare calls (fixtures/observations baseline))
        observed (fixtures/observations (merge baseline (environment/changes)))
        measure (partial capture! captured (partial fixtures/measure calls directory observed))
        output (fixtures/verify ctx prepare measure)
        request @captured
        id (:comparison/artifact-ref request)
        value (artifact/read-published directory id)]
    (is (not (anomaly/anomaly? output)))
    (is (= [:prepare :verify] (mapv first @calls)))
    (f {:ctx ctx :directory directory :request request :id id :value value :output output})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-retained [f]
  (fixtures/with-context (partial with-context f)))

(comment ::original-producer-publication-and-assembly-are-real-not-simulated)
