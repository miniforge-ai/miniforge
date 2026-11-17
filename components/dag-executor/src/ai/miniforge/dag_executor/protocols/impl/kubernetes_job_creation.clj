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

(def ^{:stratum 0 :private true} provider-reasons
  [[#"\AError from server \(AlreadyExists\):" :already-exists]
   [#"\AError from server \(Forbidden\):" :forbidden]
   [#"\A(?:Error from server \(Unauthorized\):|error: You must be logged in to the server \()" :unauthorized]
   [#"\A(?:Error from server \(Invalid\):|The (?:request|Job [^\r\n]+) is invalid(?=[:\r\n]|\z))" :invalid-job]])

(defn- ^{:stratum 0} present-text? [value]
  (and (string? value) (not (str/blank? value))))

(defn- ^{:stratum 0} unconfirmed [reason exit-code]
  (result/err :job-creation-unconfirmed
              (messages/t :kubernetes/job-creation-unconfirmed)
              (cond-> {:reason reason}
                (int? exit-code) (assoc :exit-code exit-code))))

(defn- ^{:stratum 0} parse-reply [out]
  (try+
    (json/parse-string-strict out)
    (catch InterruptedException e (throw e))
    (catch Exception _ ::malformed-response)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} command-reason [stderr]
  ;; Only exact CLI headers can contribute a reason; never keep provider text.
  (or (when (string? stderr)
        (some (fn [[header reason]] (when (re-find header stderr) reason)) provider-reasons))
      :provider-command-failed))

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
          {:keys [exit out err]} (shell/sh (or executable "kubectl")
                                      "create" "-n" (:namespace requested) "-f" "-" "-o" "json"
                                      (str "--request-timeout=" request-timeout-ms "ms")
                                      :in (json/generate-string job))]
      (if (= 0 exit)
        (let [observed (parse-reply out)]
          (if (= ::malformed-response observed)
            (unconfirmed :malformed-response exit)
            (if-let [created (receipt job observed)]
              (result/ok created)
              (unconfirmed :receipt-mismatch exit))))
        (unconfirmed (command-reason err) exit)))
    (catch InterruptedException _
      (let [failure (unconfirmed :interrupted nil)]
        (.interrupt (Thread/currentThread))
        failure))
    (catch java.io.IOException _ (unconfirmed :command-io-failed nil))
    (catch Exception _ (unconfirmed :command-failed nil))))
