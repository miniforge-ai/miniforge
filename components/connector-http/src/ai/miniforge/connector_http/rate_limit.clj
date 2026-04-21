(ns ai.miniforge.connector-http.rate-limit
  "Shared response-header-driven rate limiting for API connectors.
   Reads rate limit headers from HTTP responses and backs off when
   approaching the limit. Works with GitHub, GitLab, and any provider
   that returns standard rate limit headers."
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

;;------------------------------------------------------------------------------ Layer 0
;; Header parsing

(defn- parse-long-header
  "Parse a header value as a long, returning nil on failure."
  [headers header-name]
  (when-let [v (get headers header-name)]
    (try (Long/parseLong (str v)) (catch NumberFormatException _ nil))))

(defn parse-rate-headers
  "Extract rate limit info from response headers using a provider mapping.
   mapping: {:remaining \"x-ratelimit-remaining\" :reset \"x-ratelimit-reset\" :limit \"x-ratelimit-limit\"}
   Returns: {:remaining long :reset-epoch long :limit long} or nil if headers absent."
  [headers mapping]
  (let [remaining (parse-long-header headers (:remaining mapping))
        reset-at  (parse-long-header headers (:reset mapping))
        limit     (parse-long-header headers (:limit mapping))]
    (when (and remaining reset-at)
      {:remaining   remaining
       :reset-epoch reset-at
       :limit       limit})))

;;------------------------------------------------------------------------------ Layer 1
;; State management

(defn update-rate-state!
  "Store rate limit info in a handle's atom state."
  [handles-atom handle rate-info]
  (when rate-info
    (swap! handles-atom assoc-in [handle :rate-limit] rate-info)))

(defn- ms-until-reset
  "Milliseconds until the rate limit resets. Returns 0 if already past."
  [reset-epoch]
  (max 0 (- (* reset-epoch 1000) (System/currentTimeMillis))))

;;------------------------------------------------------------------------------ Layer 2
;; Permit acquisition

(defonce ^:private ^ScheduledExecutorService executor
  (Executors/newSingleThreadScheduledExecutor))

(def ^:private default-threshold
  "Back off when remaining requests drops below this threshold."
  10)

(defn acquire-permit!
  "Check rate limit state and wait if remaining is below threshold.
   Uses a ScheduledExecutorService for non-blocking delay.
   opts: {:threshold long} — override the default remaining threshold."
  [handles-atom handle opts]
  (when-let [rate-info (get-in @handles-atom [handle :rate-limit])]
    (let [remaining (:remaining rate-info)
          threshold (get opts :threshold default-threshold)]
      (when (and remaining (< remaining threshold))
        (let [wait-ms (ms-until-reset (:reset-epoch rate-info))]
          (when (pos? wait-ms)
            (let [p (promise)]
              (.schedule executor
                         ^Runnable (fn [] (deliver p true))
                         (long (min wait-ms 60000)) ;; cap at 60s
                         TimeUnit/MILLISECONDS)
              @p)))))))

;;------------------------------------------------------------------------------ Layer 3
;; Time-based (interval) rate limiting

(defn time-based-acquire!
  "Sleep if needed to enforce a requests-per-second limit.
   Takes rps (number) and last-request-ms (epoch millis; 0 or nil means no prior request).
   Returns current epoch-ms for use as the new last-request-at timestamp."
  [rps last-request-ms]
  (let [min-interval-ms (/ 1000.0 rps)
        elapsed         (- (System/currentTimeMillis) (or last-request-ms 0))]
    (when (< elapsed min-interval-ms)
      (Thread/sleep (long (- min-interval-ms elapsed)))))
  (System/currentTimeMillis))

(comment
  ;; Provider header mappings:
  ;; GitHub:  {:remaining "x-ratelimit-remaining" :reset "x-ratelimit-reset" :limit "x-ratelimit-limit"}
  ;; GitLab:  {:remaining "ratelimit-remaining"   :reset "ratelimit-reset"   :limit "ratelimit-limit"}
  )
