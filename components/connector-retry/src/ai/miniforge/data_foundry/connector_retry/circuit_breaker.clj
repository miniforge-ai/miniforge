(ns ai.miniforge.data-foundry.connector-retry.circuit-breaker)

(def default-config
  "Default circuit breaker configuration. Override via create-circuit-breaker opts."
  {:breaker/failure-threshold 5
   :breaker/reset-timeout-ms  60000})

(defn create-circuit-breaker
  "Create initial circuit breaker state.
   Merges provided opts over default-config."
  [opts]
  (let [{:breaker/keys [failure-threshold reset-timeout-ms]}
        (merge default-config opts)]
    {:breaker/state :closed
     :breaker/failure-count 0
     :breaker/failure-threshold failure-threshold
     :breaker/reset-timeout-ms reset-timeout-ms
     :breaker/last-failure-at nil
     :breaker/last-success-at nil}))

(defn breaker-state
  "Return effective breaker state, considering timeout for half-open transition."
  [{:breaker/keys [state last-failure-at reset-timeout-ms]} now-ms]
  (if (and (= state :open)
           last-failure-at
           (>= (- now-ms last-failure-at) reset-timeout-ms))
    :half-open
    state))

(defn allow-request?
  "Returns true if the breaker allows a request."
  [breaker now-ms]
  (let [effective (breaker-state breaker now-ms)]
    (not= effective :open)))

(defn record-success
  "Record a successful operation."
  [breaker]
  (assoc breaker
         :breaker/state :closed
         :breaker/failure-count 0
         :breaker/last-success-at (System/currentTimeMillis)))

(defn record-failure
  "Record a failed operation. Opens breaker if threshold exceeded."
  [{:breaker/keys [failure-count failure-threshold] :as breaker}]
  (let [new-count (inc failure-count)
        now (System/currentTimeMillis)]
    (if (>= new-count failure-threshold)
      (assoc breaker
             :breaker/state :open
             :breaker/failure-count new-count
             :breaker/last-failure-at now)
      (assoc breaker
             :breaker/failure-count new-count
             :breaker/last-failure-at now))))
