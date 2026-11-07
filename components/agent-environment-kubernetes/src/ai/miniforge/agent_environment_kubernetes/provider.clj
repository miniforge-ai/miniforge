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
(ns ai.miniforge.agent-environment-kubernetes.provider
  "The Kubernetes environment provider: one namespace and one pod per
   environment, the pod on the RuntimeClass its isolation level maps to.
   Every call on an existing environment first checks the namespace still
   has the target's UID, so a namespace recreated under the same name is
   never treated as the leased one."
  (:require
   [ai.miniforge.agent-environment-kubernetes.cluster :as cluster]
   [ai.miniforge.agent-environment-kubernetes.manifests :as manifests]
   [ai.miniforge.agent-environment-kubernetes.messages :as msg]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} cancel-script
  "Signal every process in the container except its keep-alive (pid 1) and
   this shell."
  "for p in /proc/[0-9]*; do n=${p#/proc/}; [ \"$n\" = 1 ] || [ \"$n\" = $$ ] || kill -TERM \"$n\" 2>/dev/null; done; true")

(def ^{:stratum 0} write-script
  "Write stdin to $1, creating its parent directory."
  "mkdir -p \"$(dirname \"$1\")\" && cat > \"$1\"")

(defn- ^{:stratum 0} step [name outcome] {:step/name name :step/outcome outcome})

(defn- ^{:stratum 0} missing [target]
  (anomaly/sub-anomaly :not-found :anomalies.agent-environment-kubernetes/target-missing
                       (msg/t :anomalies.agent-environment-kubernetes/target-missing) {:target target}))

(defn- ^{:stratum 0} exec-result [result]
  (if (anomaly/anomaly? result)
    result
    {:exec/exit (:kubectl/exit result) :exec/stdout (:kubectl/out result) :exec/stderr (:kubectl/err result)}))

(defn- ^{:stratum 0} runner-for
  "The kubectl port for one call; an exec carries its own deadline."
  [{:keys [settings runner]} timeout-ms]
  (partial runner (cond-> settings timeout-ms (assoc :kubernetes/command-timeout-ms timeout-ms))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} guarded
  "`(f run)` only while the target namespace still carries the target UID."
  [provider target timeout-ms f]
  (let [run (runner-for provider timeout-ms)]
    (if (= (:target/uid target) (:namespace/uid (cluster/namespace-identity run (:target/namespace target))))
      (f run)
      (missing target))))

(defn- ^{:stratum 1} preflight-failures [{:keys [settings] :as provider} request]
  (let [run (runner-for provider nil)
        {:runtime/keys [class node-selector]} (get-in settings [:kubernetes/runtimes (:environment/isolation request)])]
    (cond
      (not (cluster/ok? (run ["get" "--raw" "/readyz"] nil))) [:anomalies.agent-environment-kubernetes/cluster-unreachable]
      (and class (not (cluster/runtime-class? run class))) [:anomalies.agent-environment-kubernetes/runtime-class-missing]
      (not (cluster/eligible-nodes? run node-selector)) [:anomalies.agent-environment-kubernetes/no-eligible-nodes]
      :else [])))

(defn- ^{:stratum 1} provision [{:keys [settings] :as provider} target request]
  (let [run (runner-for provider nil)
        runtime (get-in settings [:kubernetes/runtimes (:environment/isolation request)])
        uid (get-in (cluster/create! run (manifests/namespace-manifest target request)) [:metadata :uid])
        pod (when uid (cluster/create! run (manifests/pod-manifest settings runtime target request)))]
    {:provision/uid uid
     :provision/steps (cond-> [(step :step/namespace (if uid :outcome/applied :outcome/failed))]
                        uid (conj (step :step/workload (if pod :outcome/applied :outcome/failed))))}))

(defn- ^{:stratum 1} observe [{:keys [settings] :as provider} target]
  (let [run (runner-for provider nil)
        known (cluster/namespace-identity run (:target/namespace target))
        uid (:namespace/uid known)
        pod (when uid (cluster/pod-state run (:target/namespace target)))
        isolation (some->> (:pod/isolation pod) (keyword "isolation"))
        expected (get-in settings [:kubernetes/runtimes isolation :runtime/class])]
    {:observed/uid uid
     :observed/generation (:namespace/generation known)
     :observed/ready? (boolean (and (:pod/ready? pod) isolation (= expected (:pod/runtime-class pod))))}))

(defn- ^{:stratum 1} written [target path text run]
  (let [result (cluster/exec run (:target/namespace target) ["sh" "-c" write-script "sh" path] text)]
    (if (cluster/ok? result) {:file/path path} (exec-result result))))

(defn- ^{:stratum 1} read-back [target path run]
  (let [result (cluster/exec run (:target/namespace target) ["cat" path] nil)]
    (if (cluster/ok? result) (:kubectl/out result) (missing target))))

(defn- ^{:stratum 1} deprovision [{:keys [settings] :as provider} target]
  (let [run (runner-for provider nil)
        namespace (:target/namespace target)
        uid (:namespace/uid (cluster/namespace-identity run namespace))
        outcome (cond
                  (cluster/namespace-absent? run namespace) :outcome/applied
                  (and (:target/uid target) (not= (:target/uid target) uid)) :outcome/failed
                  :else (cluster/delete-namespace! run namespace (:kubernetes/delete-timeout-seconds settings)))]
    {:deprovision/steps [(step :step/namespace-deleted outcome)]}))

;------------------------------------------------------------------------------ Layer 2

(defrecord ^{:stratum 2} KubernetesProvider [settings runner]
  protocol/EnvironmentProvider
  (capabilities [_]
    {:capabilities/provider :provider/kubernetes
     :capabilities/isolation (set (keys (:kubernetes/runtimes settings)))
     :capabilities/max-limits (:kubernetes/max-limits settings)})
  (probe [this request] (preflight-failures this request))
  (provision! [this target request] (provision this target request))
  (observe [this target] (observe this target))
  (exec! [this target command]
    (guarded this target (:exec/timeout-ms command)
             #(exec-result (cluster/exec % (:target/namespace target) (:exec/argv command) (:exec/stdin command)))))
  (write-file! [this target path text] (guarded this target nil (partial written target path text)))
  (read-file [this target path] (guarded this target nil (partial read-back target path)))
  (cancel! [this target]
    (let [stopped (guarded this target nil #(cluster/exec % (:target/namespace target) ["sh" "-c" cancel-script] nil))]
      {:cancel/steps [(step :step/workload-stopped (if (cluster/ok? stopped) :outcome/applied :outcome/failed))]}))
  (deprovision! [this target] (deprovision this target)))
