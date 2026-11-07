;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-container
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} non-root-user-id "The fixed worker needs no root identity or privileged ports." 65532)

(def ^{:stratum 0} scheduling-gates
  "Creation cannot start traffic; a later exact UID/revision-fenced effect must release this gate."
  [{"name" "miniforge.ai/opsv-authorized-start"}])

(def ^{:stratum 0} container-security
  "The fixed worker has no writable root, privilege escalation or Linux capabilities."
  {"allowPrivilegeEscalation" false "readOnlyRootFilesystem" true
   "capabilities" {"drop" ["ALL"]}})

(defn- ^{:stratum 0} arguments
  "Kubernetes expands argv before execution; escaped dollars preserve the literal scoped URL."
  [limits]
  (let [load (:opsv/load-limits limits)
        count (str (:load/max-requests load))
        concurrency (str (:load/max-concurrency load))
        rate (str (:load/max-rate-per-second load))
        timeout (str (:opsv/duration-ms limits) "ms")
        destination (first (:opsv/traffic-destinations limits))
        literal-destination (str/replace destination "$" "$$")]
    ["load" "-n" count "-c" concurrency "-qps" rate "-timeout" timeout
     "-nocatchup=true" "-uniform=true" "-jitter=false" "-stdclient=true"
     "-keepalive=false" "-L=false" "-k=false" "-https-insecure=false" "-json" "-" literal-destination]))

(defn- ^{:stratum 0} resources [runtime]
  (let [cpu (str (:load/cpu-millis runtime) "m")
        memory (str (:load/memory-mib runtime) "Mi")
        bound {"cpu" cpu "memory" memory}]
    {"requests" bound "limits" bound}))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} pod-security
  "A non-root worker identity with the platform's default syscall profile."
  {"runAsNonRoot" true "runAsUser" non-root-user-id "seccompProfile" {"type" "RuntimeDefault"}})

(defn ^{:stratum 1} container [input]
  (let [runtime (:runtime input)
        args (arguments (:limits input))
        resources (resources runtime)]
    {"name" "load" "image" (:load/image runtime) "imagePullPolicy" "IfNotPresent"
     "command" ["/usr/bin/fortio"] "args" args
     "resources" resources "securityContext" container-security}))

(comment ::fixed-fortio-profile-no-shell-extra-flags-or-credential-inputs)
