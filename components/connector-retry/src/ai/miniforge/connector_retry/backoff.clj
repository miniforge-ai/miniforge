(ns ai.miniforge.connector-retry.backoff)

(defn- fixed-delay
  [{:retry/keys [initial-delay-ms]}]
  initial-delay-ms)

(defn- exponential-delay
  [{:retry/keys [initial-delay-ms max-delay-ms backoff-multiplier] :or {backoff-multiplier 2.0}} attempt]
  (let [delay (* initial-delay-ms (Math/pow backoff-multiplier attempt))]
    (long (if max-delay-ms (min delay max-delay-ms) delay))))

(def default-jitter
  "Default jitter range. Override via :retry/jitter-min and :retry/jitter-max in policy."
  {:retry/jitter-min 0.5
   :retry/jitter-max 1.0})

(defn- jittered-exponential-delay
  [policy attempt]
  (let [base (exponential-delay policy attempt)
        {:retry/keys [jitter-min jitter-max]}
        (merge default-jitter policy)
        jitter (+ jitter-min (* (- jitter-max jitter-min) (Math/random)))]
    (long (* base jitter))))

(defn compute-delay
  "Compute delay in ms for given attempt (0-indexed)."
  [{:retry/keys [strategy] :as policy} attempt]
  (case strategy
    :fixed (fixed-delay policy)
    :exponential (exponential-delay policy attempt)
    :jittered-exponential (jittered-exponential-delay policy attempt)
    (throw (ex-info (str "Unknown retry strategy: " strategy) {:strategy strategy}))))

(defn should-retry?
  "Returns true if retry should be attempted."
  [{:retry/keys [max-attempts]} attempt error-category]
  (and (< attempt (dec max-attempts))
       (contains? #{:transient :rate-limited} error-category)))

(defn retry-sequence
  "Returns a sequence of delay values for all attempts."
  [{:retry/keys [max-attempts] :as policy}]
  (mapv #(compute-delay policy %) (range max-attempts)))
