(ns ai.miniforge.dag-executor.result
  "Result helpers for the DAG executor.
   Provides consistent ok/err result patterns.")

;------------------------------------------------------------------------------ Layer 0
;; Result constructors

(defn ok
  "Create a success result.

   Arguments:
   - data: Map of result data

   Returns {:ok? true :data {...}}"
  [data]
  {:ok? true :data data})

(defn err
  "Create an error result.

   Arguments:
   - code: Keyword identifying the error type
   - message: Human-readable error message
   - data: Optional additional error data

   Returns {:ok? false :error {:code :kw :message \"...\" :data {...}}}"
  ([code message]
   {:ok? false :error {:code code :message message}})
  ([code message data]
   {:ok? false :error {:code code :message message :data data}}))

(defn ok?
  "Check if a result is successful."
  [result]
  (:ok? result false))

(defn err?
  "Check if a result is an error."
  [result]
  (not (:ok? result true)))

;------------------------------------------------------------------------------ Layer 1
;; Result extraction

(defn unwrap
  "Extract data from an ok result, throw if error."
  [result]
  (if (ok? result)
    (:data result)
    (throw (ex-info "Unwrap called on error result"
                    {:error (:error result)}))))

(defn unwrap-or
  "Extract data from an ok result, or return default if error."
  [result default]
  (if (ok? result)
    (:data result)
    default))

(defn map-ok
  "Transform the data in an ok result, pass through errors."
  [result f]
  (if (ok? result)
    (ok (f (:data result)))
    result))

(defn map-err
  "Transform the error in an err result, pass through oks."
  [result f]
  (if (err? result)
    {:ok? false :error (f (:error result))}
    result))

;------------------------------------------------------------------------------ Layer 2
;; Result combinators

(defn and-then
  "Chain a function that returns a result onto an ok result.
   Passes through errors."
  [result f]
  (if (ok? result)
    (f (:data result))
    result))

(defn or-else
  "Chain a function that returns a result onto an err result.
   Passes through oks."
  [result f]
  (if (err? result)
    (f (:error result))
    result))

(defn collect
  "Collect a sequence of results into a result of sequence.
   Returns the first error if any result is an error."
  [results]
  (loop [remaining results
         collected []]
    (if (empty? remaining)
      (ok collected)
      (let [result (first remaining)]
        (if (ok? result)
          (recur (rest remaining) (conj collected (:data result)))
          result)))))

;------------------------------------------------------------------------------ Layer 3
;; Common error codes for DAG execution

(def error-codes
  "Standard error codes for DAG execution."
  #{:invalid-state
    :invalid-transition
    :task-not-found
    :dependency-cycle
    :budget-exhausted
    :max-iterations
    :concurrent-modification
    :ci-failed
    :review-rejected
    :merge-conflict
    :merge-failed
    :pr-creation-failed
    :rebase-failed
    :timeout})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create results
  (def success (ok {:task-id (random-uuid) :status :completed}))
  (def failure (err :task-not-found "Task with ID xyz not found" {:task-id "xyz"}))

  ;; Check results
  (ok? success)   ; => true
  (ok? failure)   ; => false
  (err? failure)  ; => true

  ;; Extract data
  (unwrap success)           ; => {:task-id ... :status :completed}
  (unwrap-or failure :default) ; => :default

  ;; Transform results
  (map-ok success :status)   ; => (ok :completed)

  ;; Chain results
  (and-then success (fn [data] (ok (assoc data :processed true))))
  ; => (ok {:task-id ... :status :completed :processed true})

  ;; Collect results
  (collect [(ok 1) (ok 2) (ok 3)])  ; => (ok [1 2 3])
  (collect [(ok 1) (err :x "x") (ok 3)]) ; => (err :x "x")

  :leave-this-here)
