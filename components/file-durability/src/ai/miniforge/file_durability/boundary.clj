;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary
  "Convert filesystem failures to data without claiming successful durability."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.messages.interface :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private t
  (messages/create-translator "config/file-durability/messages/system.edn"
                              :file-durability/messages))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} failure [operation file]
  (anomaly/anomaly :fault (t :file/io-failed)
                   {:durability/operation operation :durability/path (str file)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} call-with-exception-handling [operation file action]
  (try
    (action)
    file
    (catch InterruptedException _
      (let [result (failure operation file)]
        (.interrupt (Thread/currentThread))
        result))
    (catch Exception _ (failure operation file))))

(comment
  (failure :confirm nil))
