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
(ns ai.miniforge.agent-environment-kubernetes.manifests
  "The namespace and pod one environment is made of. The pod names the
   RuntimeClass its isolation level maps to, so a missing RuntimeClass
   fails admission instead of running on the node default; it mounts no
   host path and no service-account token, and runs non-root.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pod-name "workload")

(def ^{:stratum 0} container-name "workload")

(def ^{:stratum 0} workdir "/work")

(def ^{:stratum 0} unprivileged-id
  "The conventional `nobody` uid and gid every image carries."
  65534)

(def ^{:stratum 0} managed-by
  "Label value marking resources this provider created."
  "miniforge-agent-environment")

(defn ^{:stratum 0} quantities
  [limits]
  {:cpu (str (:limits/cpu-millicores limits) "m")
   :memory (str (:limits/memory-mib limits) "Mi")
   :ephemeral-storage (str (:limits/ephemeral-storage-mib limits) "Mi")})

(def ^{:stratum 0} container-security
  {:allowPrivilegeEscalation false
   :readOnlyRootFilesystem true
   :capabilities {:drop ["ALL"]}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} labels
  [target request]
  {"app.kubernetes.io/managed-by" managed-by
   "miniforge.ai/tenant-id" (str (:environment/tenant-id request))
   "miniforge.ai/run-id" (str (:environment/run-id request))
   "miniforge.ai/task-id" (str (:environment/task-id request))
   "miniforge.ai/lease-generation" (str (:target/generation target))
   "miniforge.ai/isolation" (name (:environment/isolation request))})

(def ^{:stratum 1} pod-security
  {:runAsNonRoot true
   :runAsUser unprivileged-id
   :runAsGroup unprivileged-id
   :fsGroup unprivileged-id
   :seccompProfile {:type "RuntimeDefault"}})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} namespace-manifest
  [target request]
  {:apiVersion "v1"
   :kind "Namespace"
   :metadata {:name (:target/namespace target) :labels (labels target request)}})

(defn ^{:stratum 2} pod-manifest
  "The environment's single long-lived pod. `runtime` is the isolation
   level's `{:runtime/class :runtime/node-selector}`."
  [settings runtime target request]
  (let [resources (quantities (:environment/limits request))
        container {:name container-name
                   :image (:kubernetes/image settings)
                   :command (:kubernetes/keep-alive settings)
                   :workingDir workdir
                   :resources {:requests resources :limits resources}
                   :securityContext container-security
                   :volumeMounts [{:name "work" :mountPath workdir} {:name "tmp" :mountPath "/tmp"}]}
        spec (cond-> {:restartPolicy "Never"
                      :automountServiceAccountToken false
                      :enableServiceLinks false
                      :securityContext pod-security
                      :containers [container]
                      :volumes [{:name "work" :emptyDir {}} {:name "tmp" :emptyDir {}}]}
               (:runtime/class runtime) (assoc :runtimeClassName (:runtime/class runtime))
               (seq (:runtime/node-selector runtime)) (assoc :nodeSelector (:runtime/node-selector runtime)))]
    {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name pod-name :namespace (:target/namespace target) :labels (labels target request)}
     :spec spec}))
