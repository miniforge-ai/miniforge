;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-client
  "Keep legacy transport separate from deadline-bound Pod readiness observations."
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-settings :as settings]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.result :as result]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]])
  (:import [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} terminal-phases
  "A completed or failed capsule cannot be admitted as an interactive Running Pod."
  #{"Failed" "Succeeded"})

(def ^{:stratum 0 :private true} diagnostic-limit
  "Preserve the legacy 200-character cluster availability summary."
  200)

(defn- ^{:stratum 0} interrupted []
  (let [error (result/err :pod-interrupted (messages/t :kubernetes/pod-interrupted))]
    (.interrupt (Thread/currentThread))
    error))

(defn- ^{:stratum 0} executable [path]
  (or path (:kubernetes/executable (settings/defaults))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-kubectl
  "Preserve legacy transport outside readiness; do not claim a local deadline here."
  [kubectl-path & args]
  (try+
    (apply shell/sh (executable kubectl-path) args)
    (catch Exception error
      (when (instance? InterruptedException error)
        (.interrupt (Thread/currentThread)))
      {:exit 1
       :err (.getMessage error)
       :out ""})))

(defn- ^{:stratum 1} probe-ready [executable namespace job-name until]
  (let [listing (command/run! until [executable "get" "pods" "-n" namespace
                                    "-l" (str "job-name=" job-name) "-o" "jsonpath={.items[0].metadata.name}"])
        pod-name (when (= 0 (:exit listing)) (some-> (:out listing) str/trim))
        status (when-not (str/blank? pod-name)
                 (command/run! until [executable "get" "pod" pod-name "-n" namespace
                                      "-o" "jsonpath={.status.phase}"]))
        phase (when (= 0 (:exit status)) (some-> (:out status) str/trim))]
    (cond
      (or (.isInterrupted (Thread/currentThread)) (deadline/expired? until)) nil
      (= "Running" phase) (result/ok {:pod-name pod-name
                                     :status :running})
      (contains? terminal-phases phase)
      (result/err :pod-failed (messages/t :kubernetes/pod-ended {:phase phase})))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} cluster-info [kubectl-path]
  (let [reply (run-kubectl kubectl-path "cluster-info" "--request-timeout=5s")]
    (if (zero? (:exit reply))
      {:available? true
       :cluster-info (subs (:out reply) 0 (min diagnostic-limit (count (:out reply))))}
      {:available? false
       :reason (:err reply)})))

(defn ^{:stratum 2} get-pod-for-job [kubectl-path namespace job-name]
  (let [{:keys [exit out] :as reply}
        (run-kubectl kubectl-path "get" "pods" "-n" namespace
                     "-l" (str "job-name=" job-name) "-o" "jsonpath={.items[0].metadata.name}")]
    (if (and (zero? exit) (not (str/blank? out)))
      (result/ok {:pod-name (str/trim out)})
      (result/err :pod-not-found (get reply :err (messages/t :kubernetes/pod-not-found))))))

(defn ^{:stratum 2} wait-for-pod-ready
  "Share one absolute deadline across CLI reads, capture and bounded polling sleeps."
  [kubectl-path namespace job-name {:kubernetes/keys [pod-ready-timeout-ms pod-ready-poll-interval-ms]}]
  (let [until (deadline/after-ms pod-ready-timeout-ms)
        interval-ns (* pod-ready-poll-interval-ms deadline/nanos-per-ms)
        program (executable kubectl-path)]
    (try+
      (loop []
        (cond
          (.isInterrupted (Thread/currentThread)) (interrupted)
          (deadline/expired? until) (result/err :pod-timeout (messages/t :kubernetes/pod-timeout))
          :else
          (if-let [observed (probe-ready program namespace job-name until)]
            observed
            (do
              (.sleep TimeUnit/NANOSECONDS (min interval-ns (deadline/remaining-ns until)))
              (recur)))))
      (catch InterruptedException _ (interrupted)))))

(comment (executable nil))
