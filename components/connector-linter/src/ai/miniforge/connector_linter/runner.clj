;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.connector-linter.runner
  "Run linter subprocesses and parse output via ETL mappings.

   Layer 0: Subprocess execution
   Layer 1: Run a single linter
   Layer 2: Run all linters for detected technologies"
  (:require
   [ai.miniforge.connector-linter.etl :as etl]
   [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0
;; Subprocess

(defn linter-available?
  "Check if a linter CLI is available on PATH."
  [check-cmd]
  (try
    (zero? (:exit (process/sh {:cmd check-cmd :continue true
                               :out :string :err :string})))
    (catch Exception _ false)))

(defn- run-subprocess
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

;------------------------------------------------------------------------------ Layer 1
;; Single linter execution

(defn run-linter
  "Run a single linter and parse output via ETL mapping.
   Returns {:tech keyword :violations [...] :available? bool :duration-ms int}."
  [repo-path tech-id linter-config]
  (let [{:keys [command args parser check-cmd]} linter-config
        available? (linter-available? (or check-cmd [command "--version"]))
        start-ms   (System/currentTimeMillis)]
    (if-not available?
      {:tech tech-id :violations [] :available? false :duration-ms 0}
      (let [result     (run-subprocess repo-path command args)
            output     (str (:out result))
            mapping    (etl/get-mapping parser)
            violations (if mapping
                         (etl/apply-mapping mapping output)
                         [])
            end-ms     (System/currentTimeMillis)]
        {:tech        tech-id
         :violations  violations
         :available?  true
         :duration-ms (- end-ms start-ms)}))))

;------------------------------------------------------------------------------ Layer 2
;; Multi-linter orchestration

(defn- lintable-tech
  "True when a fingerprint has a linter and is in the detected set."
  [detected-techs fingerprint]
  (and (:tech/linter fingerprint)
       (contains? detected-techs (:tech/id fingerprint))))

(defn run-all
  "Run linters for all detected technologies.
   Returns {:linter-results [...] :violations [...] :total-violations int :total-duration-ms int}."
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

(defn run-fixes
  "Run linter fix commands for detected technologies.
   Returns {:fixed [...]}."
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
