;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.connector-linter.runner
  "Run linter subprocesses and parse output via ETL mappings.

   Stratification (intra-namespace):
   Layer 0 — `linter-available?`, `run-subprocess`, `lintable-tech`
             (no in-ns deps).
   Layer 1 — `run-linter` (composes Layer 0).
   Layer 2 — `run-all`, `run-fixes` (public orchestration; compose
             Layer 1)."
  (:require
   [ai.miniforge.clock.interface :as clock]
   [ai.miniforge.connector-linter.etl :as etl]
   [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0

;; No in-namespace dependencies.
(defn ^{:stratum 0} linter-available?
  [check-cmd]
  (try
    (zero? (:exit (process/sh {:cmd check-cmd :continue true
                               :out :string :err :string})))
    (catch Exception _ false)))

(defn- ^{:stratum 0} run-subprocess
  "Run a linter subprocess. Returns {:exit int :out string :err string}."
  [repo-path command args]
  (try
    (process/sh {:cmd (into [command] args)
                 :dir (str repo-path)
                 :continue true
                 :out :string
                 :err :string})
    (catch Exception e
      {:exit -1 :out "" :err (.getMessage e)})))

(defn- ^{:stratum 0} lintable-tech
  "True when a fingerprint has a linter and is in the detected set."
  [detected-techs fingerprint]
  (and (:tech/linter fingerprint)
       (contains? detected-techs (:tech/id fingerprint))))

;------------------------------------------------------------------------------ Layer 1

;; Composes Layer 0.
(defn ^{:stratum 1} run-linter
  [repo-path tech-id linter-config]
  (let [{:keys [command args parser check-cmd]} linter-config
        available? (linter-available? (or check-cmd [command "--version"]))
        start-ms   (clock/now-ms)]
    (if-not available?
      {:tech tech-id :violations [] :available? false :duration-ms 0}
      (let [result     (run-subprocess repo-path command args)
            output     (str (:out result))
            mapping    (etl/get-mapping parser)
            violations (if mapping
                         (etl/apply-mapping mapping output)
                         [])]
        {:tech        tech-id
         :violations  violations
         :available?  true
         :duration-ms (clock/elapsed-since start-ms)}))))

(defn ^{:stratum 1} run-fixes
  [repo-path fingerprints detected-techs]
  (let [fixable (->> fingerprints
                     (filter #(lintable-tech detected-techs %))
                     (filter #(get-in % [:tech/linter :fix-args])))]
    {:fixed
     (vec (keep (fn [fp]
                  (let [{:keys [command fix-args check-cmd]} (:tech/linter fp)]
                    (when (linter-available? (or check-cmd [command "--version"]))
                      {:tech (:tech/id fp)
                       :exit (:exit (run-subprocess repo-path command fix-args))})))
                fixable))}))

;------------------------------------------------------------------------------ Layer 2

;; Public orchestration — composes Layer 1 (`run-linter`) + Layer 0
;; helpers (`lintable-tech`, `linter-available?`, `run-subprocess`).
(defn ^{:stratum 2} run-all
  [repo-path fingerprints detected-techs]
  (let [lintable (filter #(lintable-tech detected-techs %) fingerprints)
        results  (mapv (fn [fp]
                         (run-linter repo-path (:tech/id fp) (:tech/linter fp)))
                       lintable)
        all-viols (vec (mapcat :violations results))]
    {:linter-results    results
     :violations        all-viols
     :total-violations  (count all-viols)
     :total-duration-ms (reduce + 0 (map :duration-ms results))}))
