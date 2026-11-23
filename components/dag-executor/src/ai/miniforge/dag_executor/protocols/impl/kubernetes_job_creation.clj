;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-job-creation
  "Create a Job once, retaining only the matching server-provided identity."
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-submission :as submission]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.result :as result]
            [cheshire.core :as json]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} api-version
  "Only a batch/v1 reply can confirm this capsule Job request."
  "batch/v1")

(def ^{:stratum 0} resource-kind
  "A Pod or other resource is not proof of creating the requested Job."
  "Job")

(def ^{:stratum 0 :private true} transport-reasons
  "Only closed transport diagnostics can enter the creation failure record."
  {:deadline :deadline
   :incomplete-output :incomplete-output
   :interrupted :interrupted
   :output-limit :output-limit
   :input-error :input-error
   :command-io-error :command-io-failed
   :command-error :command-failed})

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

(defn- ^{:stratum 0} admission-reason [until]
  (cond
    (.isInterrupted (Thread/currentThread)) :interrupted
    (deadline/expired? until) :deadline
    :else nil))

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
      {:job-name name
       :namespace namespace
       :job-uid uid
       :job-creation-resource-version version})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create!
  "Submit once within one local deadline, admitting only an exact server receipt."
  [executable job request-timeout-ms]
  (try+
    (let [until (deadline/after-ms request-timeout-ms)
          {:keys [exit out err reason]} (submission/submit! until executable job request-timeout-ms)
          observed (when (= 0 exit) (parse-reply out))
          created (receipt job observed)
          admission-failure (admission-reason until)]
      (cond
        admission-failure (unconfirmed admission-failure nil)
        reason (unconfirmed (get transport-reasons reason :command-failed) nil)
        (not= 0 exit) (unconfirmed (command-reason err) exit)
        (= ::malformed-response observed) (unconfirmed :malformed-response exit)
        (nil? created) (unconfirmed :receipt-mismatch exit)
        :else (result/ok created)))
    (catch InterruptedException _
      (let [failure (unconfirmed :interrupted nil)]
        (.interrupt (Thread/currentThread))
        failure))
    (catch java.io.IOException _ (unconfirmed :command-io-failed nil))
    (catch Exception _ (unconfirmed :command-failed nil))))

(comment
  ;; Inspect the external JSON contract without dispatching a provider request.
  (receipt {:metadata {:name "capsule"
                       :namespace "staging"}
            :spec {:activeDeadlineSeconds 1}}
           {"apiVersion" api-version
            "kind" resource-kind
            "metadata" {"name" "capsule"
                        "namespace" "staging"
                        "uid" "job-uid"
                        "resourceVersion" "1"}
            "spec" {"activeDeadlineSeconds" 1}}))
