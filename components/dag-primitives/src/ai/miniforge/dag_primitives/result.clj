(ns ai.miniforge.dag-primitives.result
  "Result monad — consistent ok/err patterns for pipeline and DAG operations.")

;;------------------------------------------------------------------------------ Layer 0
;; Constructors

(defn ok
  "Success result.  Returns {:ok? true :data data}"
  [data]
  {:ok? true :data data})

(defn err
  "Error result.  Returns {:ok? false :error {:code kw :message str [:data map]}}"
  ([code message]
   {:ok? false :error {:code code :message message}})
  ([code message data]
   {:ok? false :error {:code code :message message :data data}}))

(defn ok?  [result] (:ok? result false))
(defn err? [result] (not (:ok? result true)))

;;------------------------------------------------------------------------------ Layer 1
;; Extraction

(defn unwrap
  "Extract data from an ok result; throw on error."
  [result]
  (if (ok? result)
    (:data result)
    (throw (ex-info "Unwrap called on error result" {:error (:error result)}))))

(defn unwrap-or
  "Extract data from an ok result, or return default on error."
  [result default]
  (if (ok? result) (:data result) default))

;;------------------------------------------------------------------------------ Layer 2
;; Transforms

(defn map-ok
  "Apply f to the data inside an ok result; pass errors through."
  [result f]
  (if (ok? result) (ok (f (:data result))) result))

(defn map-err
  "Apply f to the error inside an err result; pass oks through."
  [result f]
  (if (err? result) {:ok? false :error (f (:error result))} result))

;;------------------------------------------------------------------------------ Layer 3
;; Combinators

(defn and-then
  "Chain f (returns a result) onto an ok result; pass errors through."
  [result f]
  (if (ok? result) (f (:data result)) result))

(defn or-else
  "Chain f (returns a result) onto an err result; pass oks through."
  [result f]
  (if (err? result) (f (:error result)) result))

(defn collect
  "Collect a seq of results into a result of seq.
   Returns the first error encountered, or ok of all data values."
  [results]
  (loop [remaining results
         collected []]
    (if (empty? remaining)
      (ok collected)
      (let [r (first remaining)]
        (if (ok? r)
          (recur (rest remaining) (conj collected (:data r)))
          r)))))
