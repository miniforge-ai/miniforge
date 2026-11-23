;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-pod-observation
  "Verify the observed Pod against the exact Job creation receipt before admission."
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-defaults :as defaults]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-pod-identity :as identity]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.result :as result]
            [ai.miniforge.kubernetes-target.interface :as target]
            [cheshire.core :as json]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unconfirmed []
  (result/err :pod-owner-unconfirmed (messages/t :kubernetes/pod-owner-unconfirmed)))

(defn- ^{:stratum 0} admissible? [job pod-name]
  (and (not (.isInterrupted (Thread/currentThread)))
       (every? (partial m/validate target/IdentityText)
               [pod-name (:namespace job) (:job-name job) (:job-uid job)])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-and-confirm! [executable job request-timeout-ms pod-name]
  (let [until (deadline/after-ms request-timeout-ms)
        binary (if (nil? executable) (:executable (defaults/data)) executable)
        args [binary "get" "pod" "-n" (:namespace job) "-o" "json"
              (str "--request-timeout=" request-timeout-ms "ms") "--" pod-name]
        {:keys [exit out]} (command/run! until args)
        observed (when (= 0 exit) (identity/confirmed job pod-name (json/parse-string-strict out)))]
    (if (and observed (not (.isInterrupted (Thread/currentThread)))
             (not (deadline/expired? until)))
      (result/ok observed)
      (unconfirmed))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify! [executable job request-timeout-ms {:keys [pod-name]}]
  (if-not (admissible? job pod-name)
    (unconfirmed)
    (try+
      (read-and-confirm! executable job request-timeout-ms pod-name)
      (catch Exception e
        (when (instance? InterruptedException e)
          (.interrupt (Thread/currentThread)))
        (unconfirmed)))))

(comment (admissible? {} "example"))
