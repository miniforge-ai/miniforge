;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.run-control
  "Coordinate shared admission, load-abort requests and durable grant revocation."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.run-control-boundary :as boundary]
            [ai.miniforge.phase-opsv.run-control-cleanup :as cleanup]
            [ai.miniforge.phase-opsv.run-control-state :as state]
            [clojure.string :as str])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} run? [handle] (= :run (:kind (state/record handle))))

(defn ^{:stratum 0} stopped? [handle]
  (let [run (state/record handle)]
    (or (:stopped? (actuation/mutation-status (:fence run)))
        (:stopped? (actuation/mutation-status (:fence @(:parent run)))))))

(defn ^{:stratum 0} bound-to? [handle workflow-id directory fence]
  (let [run (state/record handle)]
    (and (= workflow-id (:workflow-id run))
         (= directory (:authority-directory run))
         (identical? fence (:fence run)))))

(defn- ^{:stratum 0} stop-run! [now run]
  (actuation/stop-mutations! (:fence run))
  (let [abort (cleanup/abort! run)
        revocations (mapv #(cleanup/revoke! run % now) @(:grants run))]
    {:workflow/id (:workflow-id run)
     :mutation-status (actuation/mutation-status (:fence run))
     :abort-requested? (true? abort)
     :abort-failure (when (anomaly/any-anomaly? abort) abort)
     :grant-revocations revocations
     :cleanup-confirmed? (and (true? abort) (every? :revoked? revocations))}))

(defn ^{:stratum 0} register! [supervisor workflow-id directory request-abort!]
  (if-not (and (= :supervisor (:kind (state/record supervisor)))
               (uuid? workflow-id) (string? directory) (not (str/blank? directory))
               (fn? request-abort!))
    (boundary/failure :invalid-input :invalid-registration)
    (if-let [handle (state/register! supervisor workflow-id directory request-abort!)]
      {:control handle :fence (:fence (state/record handle))}
      (boundary/failure :conflict :registration-refused))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} at-boundary! [handle operation]
  (if (and (run? handle) (fn? operation))
    (let [run (state/record handle)]
      (actuation/at-mutation-boundary! (:fence @(:parent run))
        #(actuation/at-mutation-boundary! (:fence run) operation)))
    (boundary/failure :invalid-input :invalid-run)))

(defn ^{:stratum 1} track-grant! [handle issued now]
  (let [run (state/record handle)]
    (swap! (:grants run) conj (:grant/id issued))
    (if-not (stopped? handle)
      issued
      (let [result (cleanup/revoke! run (:grant/id issued) now)]
        (if (anomaly/any-anomaly? result) result
            (assoc-in (boundary/failure :unavailable :stopped) [:anomaly/data :opsv/stopped?] true))))))

(defn ^{:stratum 1} stop! [supervisor now]
  (if (and (= :supervisor (:kind (state/record supervisor))) (instance? Instant now))
    (let [runs (state/stop-snapshot! supervisor)
          _ (doseq [run runs] (actuation/stop-mutations! (:fence run)))
          reports (mapv (partial stop-run! now) runs)]
      {:stopped? true :runs reports :cleanup-confirmed? (every? :cleanup-confirmed? reports)
       :effects-settled? (every? #(zero? (get-in % [:mutation-status :in-flight])) reports)})
    (boundary/failure :invalid-input :invalid-stop)))

(defn ^{:stratum 1} retire! [handle now]
  (if-not (and (run? handle) (instance? Instant now))
    (boundary/failure :invalid-input :invalid-run)
    (let [report (stop-run! now (state/record handle))
          settled? (and (:cleanup-confirmed? report) (zero? (get-in report [:mutation-status :in-flight])))]
      (when settled? (state/forget! handle))
      (assoc report :retired? settled?))))
