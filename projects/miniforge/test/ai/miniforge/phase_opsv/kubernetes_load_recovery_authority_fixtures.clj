;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-recovery-authority-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.governed-load-proposal-fixtures :as load]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} with-stores [{:keys [directory] :as fixture}]
  (assoc fixture
         :recovery/breach-directory (str (fs/path directory "recovery-breaches"))
         :recovery/grants-directory (str (fs/path directory "recovery-grants"))))

(defn- ^{:stratum 0} registered! [fixture issued]
  (let [registered (grant/register! (:recovery/grants-directory fixture) issued)
        retained? (and (uuid? (:grant/id registered)) (= (:grant/id issued) (:grant/id registered)))]
    (is retained?)
    (when retained? issued)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} issue! [fixture proposal]
  (let [status (get fixture :workflow-run/status :cancelled)
        issuance (assoc (load/issuance-request proposal) :workflow-run/status status)
        issued (grant/issue-for-effect (:recovery/breach-directory fixture) issuance clock/now)
        issued? (and (not (anomaly/anomaly? issued)) (uuid? (:grant/id issued)))]
    (is issued?)
    (when issued? (registered! fixture issued))))

(comment
  (with-stores {:directory "/tmp/example"}))
