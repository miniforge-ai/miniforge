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

(def ^{:stratum 0} still-running-exit
  "Exit status of `cancel-script` when something besides the keep-alive is
   still running after the signal."
  3)

(def ^{:stratum 0} write-script
  "Write stdin to $1, creating its parent directory."
  "mkdir -p \"$(dirname \"$1\")\" && cat > \"$1\"")

(defn- ^{:stratum 0} step [name outcome] {:step/name name :step/outcome outcome})

(defn- ^{:stratum 0} missing [target]
  (anomaly/sub-anomaly :not-found :anomalies.agent-environment-kubernetes/target-missing
                       (msg/t :anomalies.agent-environment-kubernetes/target-missing) {:target target}))

(defn- ^{:stratum 0} file-failed
  "A runner anomaly as it is; a nonzero exit as `reason`, keeping the path,
   exit and output."
  [type reason path result]
  (if (anomaly/anomaly? result)
    result
    (anomaly/sub-anomaly type reason (msg/t reason) {:file/path path :exec/exit (:kubectl/exit result)
                                                     :exec/stdout (:kubectl/out result) :exec/stderr (:kubectl/err result)})))

(defn- ^{:stratum 0} exec-result [result]
  (if (anomaly/anomaly? result)
    result
    {:exec/exit (:kubectl/exit result) :exec/stdout (:kubectl/out result) :exec/stderr (:kubectl/err result)}))

(defn- ^{:stratum 0} runner-for
  "The kubectl port for one call; an exec carries its own deadline."
  [{:keys [settings runner]} timeout-ms]
  (partial runner (cond-> settings timeout-ms (assoc :kubernetes/command-timeout-ms timeout-ms))))

(defn- ^{:stratum 0} namespace-outcome
  "A create the API refused is failed. One whose answer was lost, such as on
   a deadline, is uncertain unless the namespace is then confirmed absent,
   so the lifecycle recovers it by generation and cleans it up."
  [run namespace created uid]
  (cond
    uid :outcome/applied
    (or (cluster/refused? created) (cluster/namespace-absent? run namespace)) :outcome/failed
    :else :outcome/uncertain))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} cancel-script
  "Signal every process in the container except the keep-alive (pid 1), the
   `sleep` it runs and this shell, wait a second, then exit
   `still-running-exit` if any is left. Zombies hold no work. A process gone
   before it is read is spared; one whose status cannot be parsed is not."
  (str "spared() { [ \"$1\" = 1 ] || [ \"$1\" = $$ ] || { read -r _ c s pp _ 2>/dev/null < /proc/$1/stat || return 0; "
       "[ \"$s\" = Z ] || { [ \"$c\" = \"(sleep)\" ] && [ \"$pp\" = 1 ]; }; }; }; "
       "for p in /proc/[0-9]*; do spared \"${p#/proc/}\" || kill -TERM \"${p#/proc/}\" 2>/dev/null; done; "
       "sleep 1; for p in /proc/[0-9]*; do spared \"${p#/proc/}\" || exit " still-running-exit "; done"))

(defn- ^{:stratum 1} cancel-outcome
  "Applied only when nothing but the keep-alive is left; uncertain when
   something still runs after the signal; failed when the check did not run."
  [result]
  (cond
    (cluster/ok? result) :outcome/applied
    (and (not (anomaly/anomaly? result)) (= still-running-exit (:kubectl/exit result))) :outcome/uncertain
    :else :outcome/failed))

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
        created (cluster/create! run (manifests/namespace-manifest target request))
        uid (get-in (cluster/parsed created) [:metadata :uid])
        pod (when uid (cluster/ok? (cluster/create! run (manifests/pod-manifest settings runtime target request))))]
    {:provision/uid uid
     :provision/steps (cond-> [(step :step/namespace (namespace-outcome run (:target/namespace target) created uid))]
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
    (if (cluster/ok? result)
      {:file/path path}
      (file-failed :unavailable :anomalies.agent-environment-kubernetes/file-write-failed path result))))

(defn- ^{:stratum 1} read-back [target path run]
  (let [result (cluster/exec run (:target/namespace target) ["cat" path] nil)]
    (if (cluster/ok? result)
      (:kubectl/out result)
      (file-failed :not-found :anomalies.agent-environment-kubernetes/file-read-failed path result))))

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
      {:cancel/steps [(step :step/workload-stopped (cancel-outcome stopped))]}))
  (deprovision! [this target] (deprovision this target)))
