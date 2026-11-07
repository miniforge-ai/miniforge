;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.agent-environment-kubernetes.cluster
  "Cluster reads and writes as data, through a `run` port shaped like
   `boundary.kubectl/run`. A failed kubectl call is a value the provider
   turns into a failed step or a preflight reason; nothing here throws."
  (:require
   [ai.miniforge.agent-environment-kubernetes.manifests :as manifests]
   [ai.miniforge.anomaly.interface :as anomaly]
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} ok? [result]
  (and (not (anomaly/anomaly? result)) (zero? (:kubectl/exit result))))

(defn ^{:stratum 0} selector-arg [selector]
  (str/join "," (map (fn [[k v]] (str k "=" v)) (sort selector))))

(defn ^{:stratum 0} ready? [pod]
  (boolean (some #(and (= "Ready" (:type %)) (= "True" (:status %))) (get-in pod [:status :conditions]))))

(defn ^{:stratum 0} namespace-absent?
  "True only when the API server says `namespace` is not found; an
   unreachable cluster is not evidence of absence."
  [run namespace]
  (let [result (run ["get" "namespace" namespace "-o" "name"] nil)]
    (and (not (anomaly/anomaly? result))
         (not (zero? (:kubectl/exit result)))
         (str/includes? (str (:kubectl/err result)) "NotFound"))))

(defn ^{:stratum 0} exec
  "Run `argv` in the workload container with `stdin`: kubectl's own result,
   whose exit is the command's exit."
  [run namespace argv stdin]
  (run (into ["exec" "-i" "-n" namespace manifests/pod-name "-c" manifests/container-name "--"] argv) stdin))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} parsed [result]
  (when (ok? result) (json/parse-string (:kubectl/out result) true)))

(defn ^{:stratum 1} runtime-class? [run class-name]
  (ok? (run ["get" "runtimeclass" class-name "-o" "name"] nil)))

(defn ^{:stratum 1} eligible-nodes? [run selector]
  (let [args (cond-> ["get" "nodes" "-o" "name"] (seq selector) (into ["-l" (selector-arg selector)]))
        result (run args nil)]
    (and (ok? result) (not (str/blank? (:kubectl/out result))))))

(defn ^{:stratum 1} delete-namespace!
  "Delete `namespace` and wait: `:outcome/applied` only when it is then
   confirmed gone, `:outcome/uncertain` when the delete was accepted but
   not confirmed, `:outcome/failed` when it was refused."
  [run namespace timeout-seconds]
  (let [deleted (run ["delete" "namespace" namespace "--wait=true" (str "--timeout=" timeout-seconds "s")] nil)]
    (cond
      (not (ok? deleted)) :outcome/failed
      (namespace-absent? run namespace) :outcome/applied
      :else :outcome/uncertain)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} namespace-identity
  "`{:namespace/uid s :namespace/generation n-or-nil}`: the UID of
   `namespace` and the lease generation labelled on it at provision; nil
   when it does not exist or cannot be read."
  [run namespace]
  (when-let [metadata (:metadata (parsed (run ["get" "namespace" namespace "-o" "json"] nil)))]
    {:namespace/uid (:uid metadata)
     :namespace/generation (some-> (get-in metadata [:labels :miniforge.ai/lease-generation]) parse-long)}))

(defn ^{:stratum 2} create!
  "Create one manifest; the created object, or nil when refused."
  [run manifest]
  (parsed (run ["create" "-f" "-" "-o" "json"] (json/generate-string manifest))))

(defn ^{:stratum 2} pod-state
  "`{:pod/ready? bool :pod/runtime-class s-or-nil}` for the workload pod."
  [run namespace]
  (let [pod (parsed (run ["get" "pod" manifests/pod-name "-n" namespace "-o" "json"] nil))]
    {:pod/ready? (ready? pod)
     :pod/runtime-class (get-in pod [:spec :runtimeClassName])
     :pod/isolation (get-in pod [:metadata :labels :miniforge.ai/isolation])}))
