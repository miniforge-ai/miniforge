;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.chain-retention
  "Validate public admission values and uncached recovery input before use."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.redaction.interface :as redaction]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.chain-retention-record :as record]
            [ai.miniforge.workflow.chain-retention-store :as store]
            [ai.miniforge.workflow.chain-run-record :as run-record]
            [ai.miniforge.workflow.chain-run-spec :as spec]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} inspectable [value]
  (if (and (redaction/supported? value) (redaction/clean? value)) true
      (record/failure :invalid-input :chain/retention-unsafe)))

(defn- ^{:stratum 0} valid-record [value]
  (let [detached (run-record/detach value)]
    (if (schema/valid? spec/Record detached) detached
        (record/failure :invalid-input :chain/retention-invalid))))

(defn- ^{:stratum 0} read-run [directory id]
  (or (artifact/read-published directory id)
      (record/failure :not-found :chain/retention-missing)))

(defn- ^{:stratum 0} persist-record! [directory admitted]
  (anomaly/let-ok [artifacts (record/artifacts admitted)]
    (store/persist! directory artifacts)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} persist! [directory value]
  (anomaly/let-ok [_safe (inspectable value)
                   admitted (valid-record value)]
    (persist-record! directory admitted)))

(defn ^{:stratum 1} persist-admitted!
  "Retain a producer-validated invocation; inspect storage privacy without repeating schema admission."
  [directory admitted]
  (anomaly/let-ok [_safe (inspectable admitted)]
    (persist-record! directory admitted)))

(defn ^{:stratum 1} load-run [directory id]
  (anomaly/let-ok [actual (read-run directory id)
                   _safe (inspectable actual)
                   admitted (valid-record (get-in actual [:artifact/content :chain/record]))
                   artifacts (record/artifacts admitted)]
    (store/recover directory actual artifacts)))

(comment
  ::chain-admission-storage-boundary)
