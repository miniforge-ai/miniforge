(ns ai.miniforge.response.builder
  "Canonical builders for common response patterns.

   Eliminates scattered inline map constructions by providing
   standard builders for success/error responses with metrics.")

;------------------------------------------------------------------------------ Layer 0
;; Success responses

(defn success
  "Create a canonical success response.

   Arguments:
   - output: The main result data
   - opts: Optional map with :metrics, :artifact, :tokens, :duration-ms

   Returns:
   {:status :success
    :output output
    :artifact artifact-or-output
    :metrics {:tokens N :duration-ms N}}

   Examples:
     (success test-artifact {:tokens 100 :duration-ms 500})
     (success code-data {:metrics {:tokens 200} :artifact processed-code})"
  ([output]
   (success output {}))
  ([output {:keys [metrics artifact tokens duration-ms]}]
   (let [default-metrics {:tokens (or tokens 0)
                         :duration-ms (or duration-ms 0)}
         merged-metrics (merge default-metrics metrics)]
     (cond-> {:status :success
              :output output
              :metrics merged-metrics}
       artifact (assoc :artifact artifact)
       (not artifact) (assoc :artifact output)))))

;------------------------------------------------------------------------------ Layer 1
;; Error responses

(defn error-details
  "Create canonical error details map.

   Arguments:
   - message: Error message string or Exception
   - data: Optional error data map

   Returns:
   {:message string :data map}

   Examples:
     (error-details \"Validation failed\" {:field :email})
     (error-details ex (ex-data ex))"
  ([message]
   (error-details message nil))
  ([message data]
   (let [msg (if (instance? Exception message)
              (.getMessage ^Exception message)
              (str message))
         err-data (if (instance? Exception message)
                   (or data (ex-data message) {})
                   (or data {}))]
     {:message msg
      :data err-data})))

(defn error
  "Create a canonical error response.

   Arguments:
   - message: Error message string or Exception
   - opts: Optional map with :data, :metrics, :output, :tokens, :duration-ms

   Returns:
   {:status :error
    :error {:message string :data map}
    :metrics {:tokens N :duration-ms N}
    :output output-if-provided}

   Examples:
     (error \"Test failed\" {:tokens 50 :duration-ms 200})
     (error ex {:data (ex-data ex) :metrics {:tokens 100}})"
  ([message]
   (error message {}))
  ([message {:keys [data metrics output tokens duration-ms]}]
   (let [default-metrics {:tokens (or tokens 0)
                         :duration-ms (or duration-ms 0)}
         merged-metrics (merge default-metrics metrics)]
     (cond-> {:status :error
              :error (error-details message data)
              :metrics merged-metrics}
       output (assoc :output output)))))

(defn failure
  "Create a canonical failure response (alias for error with :success false).

   Same as error but uses :success false instead of :status :error
   for backward compatibility.

   Examples:
     (failure \"Agent timeout\" {:tokens 0 :duration-ms 5000})"
  ([message]
   (failure message {}))
  ([message opts]
   (-> (error message opts)
       (dissoc :status)
       (assoc :success false))))

;------------------------------------------------------------------------------ Layer 2
;; Status checks (timestamped observations)

(defn status-check
  "Create a timestamped status check response.

   Use for point-in-time observations like health checks, monitoring, polling.
   Always includes :checked-at timestamp. For immediate operation results without
   timestamps, use success/error instead.

   Arguments:
   - status - Status keyword (:healthy, :warning, :halt, or custom)
   - opts - Optional map with :message, :data, :agent/id, or other context

   Returns:
   {:status status
    :checked-at java.time.Instant
    ...opts}

   Examples:
     (status-check :healthy
                   {:agent/id :progress-monitor
                    :message \"Workflow active: 10 chunks\"
                    :data {:chunks 10}})

     (status-check :halt
                   {:halting-agent :test-quality
                    :halt-reason \"Coverage too low\"
                    :checks [...]})"
  [status opts]
  (merge {:status status
          :checked-at (java.time.Instant/now)}
         opts))

;------------------------------------------------------------------------------ Layer 3
;; Validation errors

(defn validation-error
  "Create validation error entry.

   Arguments:
   - error: Error string or keyword

   Returns:
   String for error message

   Examples:
     (validation-error :missing-field)
     (validation-error \"Invalid email format\")"
  [error]
  (if (keyword? error)
    (name error)
    (str error)))

(defn validation-result
  "Create validation result map.

   Arguments:
   - errors: Vector of error strings (empty = valid)

   Returns:
   {:valid? boolean :errors vector}

   Examples:
     (validation-result [])
     (validation-result [\"Missing field\" \"Invalid format\"])"
  [errors]
  (if (empty? errors)
    {:valid? true}
    {:valid? false
     :errors (vec errors)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Success responses
  (success {:test/id 123} {:tokens 100 :duration-ms 500})
  ;; => {:status :success
  ;;     :output {:test/id 123}
  ;;     :artifact {:test/id 123}
  ;;     :metrics {:tokens 100 :duration-ms 500}}

  ;; Error responses
  (error "Failed to compile" {:tokens 50 :duration-ms 200})
  ;; => {:status :error
  ;;     :error {:message "Failed to compile" :data {}}
  ;;     :metrics {:tokens 50 :duration-ms 200}}

  (error (ex-info "Timeout" {:phase :verify}))
  ;; => {:status :error
  ;;     :error {:message "Timeout" :data {:phase :verify}}
  ;;     :metrics {:tokens 0 :duration-ms 0}}

  ;; Validation
  (validation-result [])
  ;; => {:valid? true}

  (validation-result ["Missing field" "Invalid email"])
  ;; => {:valid? false :errors ["Missing field" "Invalid email"]}

  ;; Status checks (timestamped)
  (status-check :healthy {:agent/id :progress-monitor
                          :message "Making progress"
                          :data {:chunks 5}})
  ;; => {:status :healthy
  ;;     :checked-at #inst "2026-01-31T..."
  ;;     :agent/id :progress-monitor
  ;;     :message "Making progress"
  ;;     :data {:chunks 5}}

  :end)
