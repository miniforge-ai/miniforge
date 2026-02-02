(ns ai.miniforge.release-executor.result
  "Result builders for release-executor operations.
   Provides consistent result structures across all operations.")

;------------------------------------------------------------------------------ Layer 0
;; Shell operation results

(defn shell-success
  "Create a shell operation success result."
  [data]
  (merge {:success? true} data))

(defn shell-failure
  "Create a shell operation failure result."
  ([error-msg]
   (shell-failure error-msg {}))
  ([error-msg data]
   (merge {:success? false :error error-msg} data)))

;------------------------------------------------------------------------------ Layer 1
;; Phase execution results

(defn phase-success
  "Create a release phase success result."
  [artifacts metrics]
  {:success? true
   :artifacts artifacts
   :errors []
   :metrics metrics})

(defn phase-failure
  "Create a release phase failure result."
  ([error-type error-msg]
   (phase-failure error-type error-msg {}))
  ([error-type error-msg opts]
   {:success? false
    :artifacts []
    :errors [(merge {:type error-type :message error-msg}
                    (select-keys opts [:hint :data]))]
    :metrics (or (:metrics opts) {})}))
