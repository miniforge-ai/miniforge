;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-job-creation
  "Create a Job once, retaining only the matching server-provided identity."
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.result :as result]
            [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} api-version "batch/v1")

(def ^{:stratum 0} resource-kind "Job")

(defn- ^{:stratum 0} present-text? [value]
  (and (string? value) (not (str/blank? value))))

(defn- ^{:stratum 0} unconfirmed []
  (result/err :job-creation-unconfirmed (messages/t :kubernetes/job-creation-unconfirmed)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} receipt [job response]
  (let [{:keys [name namespace]} (:metadata job)
        observed (get response "metadata")
        uid (get observed "uid")
        version (get observed "resourceVersion")]
    (when (and (= api-version (get response "apiVersion"))
               (= resource-kind (get response "kind"))
               (= name (get observed "name"))
               (= namespace (get observed "namespace"))
               (= (get-in job [:spec :activeDeadlineSeconds])
                  (get-in response ["spec" "activeDeadlineSeconds"]))
               (every? present-text? [name namespace uid version]))
      {:job-name name :namespace namespace :job-uid uid :job-creation-resource-version version})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create! [executable job request-timeout-ms]
  (try+
    (let [requested (:metadata job)
          {:keys [exit out]} (shell/sh (or executable "kubectl")
                                      "create" "-n" (:namespace requested) "-f" "-" "-o" "json"
                                      (str "--request-timeout=" request-timeout-ms "ms")
                                      :in (json/generate-string job))
          created (when (= 0 exit) (receipt job (json/parse-string-strict out)))]
      (if created (result/ok created) (unconfirmed)))
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      (unconfirmed))
    (catch Exception _ (unconfirmed))))
