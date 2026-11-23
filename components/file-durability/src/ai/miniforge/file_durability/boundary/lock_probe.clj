;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.lock-probe
  "Start a distinct trusted runtime, then verify genuine mutual file-lock exclusion."
  (:require [ai.miniforge.file-durability.boundary.directory-policy :as directory-policy]
            [ai.miniforge.file-durability.boundary.lock-protocol :as protocol]
            [ai.miniforge.file-durability.boundary.lock-peer :as peer]
            [ai.miniforge.file-durability.boundary.native-probe :as native]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} probe-prefix
  "Each handshake owns a new private directory and cannot reuse stale markers."
  "miniforge-lock-probe-")

(def ^{:stratum 0 :private true} exit-timeout-ms
  "Require bounded clean peer exit before verifying host lock reacquisition."
  5000)

(defn- ^{:stratum 0} start-peer! [child root configuration acquired?]
  (when acquired?
    (vreset! child (peer/launch! root configuration))
    (protocol/await? root :peer-excluded)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} confirm-peer-held! [root child]
  (and (protocol/signal! root :host-released)
       (protocol/await? root :peer-holding)
       (protocol/with-attempt root false?)
       (protocol/signal! root :host-observed-exclusion)
       (= 0 (:exit (deref child exit-timeout-ms nil)))
       (protocol/with-attempt root true?)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check! [directory peer]
  (fs/with-temp-dir [root {:dir directory :prefix probe-prefix
                          :posix-file-permissions directory-policy/private-permissions}]
    (let [child (volatile! nil)]
      (protocol/allocate! root)
      (try+
        (and (protocol/with-attempt root (partial start-peer! child root peer))
             (confirm-peer-held! root @child))
        (finally
          (when-let [started @child] (native/stop! started)))))))

(comment (protocol/runtime-kind))
