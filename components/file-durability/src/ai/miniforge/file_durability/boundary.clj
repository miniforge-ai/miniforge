;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary
  "Convert filesystem failures to data without claiming successful durability."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.messages.interface :as messages]
            [slingshot.slingshot :refer [try+ throw+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private t
  (messages/create-translator "config/file-durability/messages/system.edn"
                              :file-durability/messages))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} refuse-directory! [path]
  (throw+ (anomaly/sub-anomaly :unauthorized :anomalies.file-durability/unsafe-directory
                              (t :file/unsafe-directory {:path (str path)})
                              {:durability/path (str path)})))

(defn ^{:stratum 1} invalid-input [operation]
  (anomaly/anomaly :invalid-input (t :file/invalid-input)
                   {:durability/operation operation}))

(defn ^{:stratum 1} refuse-acl! [path]
  (let [location (str path)]
    (throw+ (anomaly/sub-anomaly :unauthorized :anomalies.file-durability/unsafe-acl
                                (t :file/unsafe-acl {:path location})
                                {:durability/path location}))))

(defn ^{:stratum 1} unverified-publication [path]
  (let [location (str path)]
    (anomaly/anomaly :fault (t :file/unverified-publication {:path location})
                     {:durability/path location})))

(defn- ^{:stratum 1} failure [operation file]
  (anomaly/anomaly :fault (t :file/io-failed)
                   {:durability/operation operation :durability/path (str file)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} call-with-exception-handling
  ([operation file action]
   (call-with-exception-handling operation file action (constantly file)))
  ([operation file action completed-value]
   (try+
     (completed-value (action))
     (catch InterruptedException _
       (let [result (failure operation file)]
         (.interrupt (Thread/currentThread))
         result))
     (catch anomaly/anomaly? result result)
     (catch Exception _ (failure operation file)))))

(comment
  (failure :confirm nil))
