;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.dependency-health
  "Pure dependency-health projection from classified dependency incidents.

   Dependency health is modeled separately from workflow execution state. This
   namespace owns rolling projection over canonical dependency incidents and
   optional recovery signals."
  (:require
   [ai.miniforge.failure-classifier.interface :as failure-classifier]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Config and constants

(def ^:private defaults
  (-> (io/resource "config/reliability/defaults.edn") slurp edn/read-string))

(def default-config
  "Default dependency-health projection config."
  (:dependency-health defaults))

(def ^:private dependency-kind-by-source
  {:external-provider :provider
   :external-platform :platform
   :user-env :environment})

;------------------------------------------------------------------------------ Layer 1
;; Helpers

(defn- dependency-id
  [{vendor :failure/vendor source :failure/source}]
  (or vendor source))

(defn- dependency-kind
  [source]
  (get dependency-kind-by-source source :environment))

(defn- incident-status
  [incident config]
  (let [retryability (:dependency/retryability incident)
        dependency-class (:dependency/class incident)
        class-status (get-in config [:class->status dependency-class] :degraded)]
    (if (= retryability :operator-action)
      :operator-action-required
      class-status)))

(defn- incident-observed-at
  [incident default-instant]
  (or (:dependency/observed-at incident)
      (:event/timestamp incident)
      default-instant))

(defn- recovery-observed-at
  [recovery default-instant]
  (or (:dependency/recovered-at recovery)
      (:event/timestamp recovery)
      default-instant))

(defn- status-counts
  [observations]
  (frequencies (map :dependency/status observations)))

(defn- satisfied-threshold-status
  [counts {:keys [status-precedence status-thresholds]}]
  (some (fn [status]
          (let [threshold (get status-thresholds status Long/MAX_VALUE)
                count (get counts status 0)]
            (when (>= count threshold)
              status)))
        status-precedence))

(defn- projected-status
  [observations config]
  (or (some-> observations status-counts (satisfied-threshold-status config))
      :healthy))

(defn- trim-observations
  [observations window-size]
  (->> observations
       (take-last window-size)
       vec))

(defn- base-entry
  [incident]
  (let [source (:failure/source incident)
        vendor (:failure/vendor incident)]
    (cond-> {:dependency/id (dependency-id incident)
             :dependency/source source
             :dependency/kind (dependency-kind source)}
      vendor (assoc :dependency/vendor vendor))))

(defn- observation
  [incident observed-at config]
  (let [status (incident-status incident config)]
    {:dependency/status status
     :dependency/class (:dependency/class incident)
     :dependency/retryability (:dependency/retryability incident)
     :failure/class (:failure/class incident)
     :dependency/observed-at observed-at}))

(defn- normalize-entry
  [entry incident]
  (merge (base-entry incident) entry))

(defn dependency-incident?
  "Returns true when the classified incident belongs in dependency-health
   projection."
  [incident]
  (failure-classifier/dependency-failure? incident))

(defn- register-incident
  [state incident config default-instant]
  (let [entry-id (dependency-id incident)
        observed-at (incident-observed-at incident default-instant)
        new-observation (observation incident observed-at config)
        window-size (:window-size config)]
    (update state entry-id
            (fn [entry]
              (let [existing-observations (:dependency/observations entry)
                    observations (-> existing-observations
                                     (conj new-observation)
                                     (trim-observations window-size))]
                (-> entry
                    (normalize-entry incident)
                    (assoc :dependency/observations observations
                           :dependency/last-observed-at observed-at)))))))

(defn- recovery-id
  [recovery]
  (or (:dependency/id recovery)
      (:failure/vendor recovery)
      (:failure/source recovery)))

(defn- recovery-entry
  [entry recovery recovered-at]
  (let [source (or (:dependency/source entry) (:failure/source recovery))
        vendor (or (:dependency/vendor entry) (:failure/vendor recovery))
        resolved-id (or (recovery-id recovery) (:dependency/id entry))]
    (cond-> {:dependency/id resolved-id
             :dependency/source source
             :dependency/kind (dependency-kind source)
             :dependency/observations []
             :dependency/last-recovered-at recovered-at}
      vendor (assoc :dependency/vendor vendor)
      (:dependency/last-observed-at entry)
      (assoc :dependency/last-observed-at (:dependency/last-observed-at entry)))))

(defn- register-recovery
  [state recovery default-instant]
  (let [entry-id (recovery-id recovery)
        recovered-at (recovery-observed-at recovery default-instant)]
    (if entry-id
      (update state entry-id
              (fn [entry]
                (recovery-entry entry recovery recovered-at)))
      state)))

(defn- project-entry
  [entry config]
  (let [observations (:dependency/observations entry)
        counts (status-counts observations)
        status (projected-status observations config)
        latest-observation (last observations)
        failure-count (count observations)
        last-observed-at (:dependency/last-observed-at entry)
        last-recovered-at (:dependency/last-recovered-at entry)
        dependency-class (:dependency/class latest-observation)
        retryability (:dependency/retryability latest-observation)
        failure-class (:failure/class latest-observation)
        window-size (:window-size config)]
    (cond-> {:dependency/id (:dependency/id entry)
             :dependency/source (:dependency/source entry)
             :dependency/kind (:dependency/kind entry)
             :dependency/status status
             :dependency/failure-count failure-count
             :dependency/window-size window-size
             :dependency/incident-counts counts}
      (:dependency/vendor entry) (assoc :dependency/vendor (:dependency/vendor entry))
      dependency-class (assoc :dependency/class dependency-class)
      retryability (assoc :dependency/retryability retryability)
      failure-class (assoc :failure/class failure-class)
      last-observed-at (assoc :dependency/last-observed-at last-observed-at)
      last-recovered-at (assoc :dependency/last-recovered-at last-recovered-at))))

;------------------------------------------------------------------------------ Layer 2
;; Public pipeline

(defn apply-signals
  "Apply dependency incidents and recovery signals to rolling state.

   Arguments:
     dependency-state - map keyed by dependency id
     incidents        - vector of canonical classified dependency failures
     recoveries       - vector of recovery signals
     config           - optional dependency-health config
     observed-at      - fallback timestamp when signals omit one"
  ([dependency-state incidents recoveries]
   (apply-signals dependency-state incidents recoveries default-config (java.util.Date.)))
  ([dependency-state incidents recoveries config observed-at]
   (let [effective-config (merge default-config config)
         filtered-incidents (filter dependency-incident? incidents)
         state-with-incidents (reduce #(register-incident %1 %2 effective-config observed-at)
                                      (or dependency-state {})
                                      filtered-incidents)]
     (reduce #(register-recovery %1 %2 observed-at)
             state-with-incidents
             recoveries))))

(defn project-health
  "Project rolling dependency state into canonical dependency-health entities."
  ([dependency-state]
   (project-health dependency-state default-config))
  ([dependency-state config]
   (let [effective-config (merge default-config config)]
     (into {}
           (map (fn [[entry-id entry]]
                  [entry-id (project-entry entry effective-config)]))
           dependency-state))))

