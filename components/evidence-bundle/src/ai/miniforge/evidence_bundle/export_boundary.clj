;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.export-boundary
  "Validate the exact immutable value written by the manager export boundary."
  (:require [ai.miniforge.evidence-bundle.edn-codec :as codec]
            [ai.miniforge.evidence-bundle.publication-validation :as validation]
            [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} write-bundle! [logger bundle-id bundle output-path]
  (spit output-path (codec/encode bundle) :encoding "UTF-8")
  (log/info logger :evidence-bundle :bundle/exported
            {:data {:bundle-id bundle-id :output-path output-path}})
  true)

(defn- ^{:stratum 0} invalid-bundle [logger bundle-id report]
  (log/error logger :evidence-bundle :bundle/export-failed
             {:data {:bundle-id bundle-id :validation/errors (:errors report)}})
  false)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} export-validated! [bundles logger bundle-id output-path]
  (let [bundle (get @bundles bundle-id)
        report (validation/validate bundle)]
    (if (:valid? report)
      (write-bundle! logger bundle-id bundle output-path)
      (invalid-bundle logger bundle-id report))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} export-with-exception-handling! [bundles logger bundle-id output-path]
  ;; No slingshot dependency; this is the filesystem boundary.
  (try
    (export-validated! bundles logger bundle-id output-path)
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Exception e
      (log/error logger :evidence-bundle :bundle/export-failed
                 {:data {:bundle-id bundle-id :error (.getMessage e)}})
      false)))

(comment
  ::export-with-exception-handling!)
