;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.probe-resources
  "Own private scratch until the registered native process snapshot is confirmed dead."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.directory-policy :as directory]
            [ai.miniforge.file-durability.boundary.native-process-exit :as exit]
            [ai.miniforge.file-durability.boundary.probe-failure-context :as failure]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+ throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} open-session! [parent prefix]
  (let [root (fs/create-temp-dir {:dir parent
                                 :prefix prefix
                                 :posix-file-permissions directory/private-permissions})
        child (volatile! nil)
        result (volatile! nil)
        primary (volatile! nil)
        unreaped (volatile! nil)]
    {:root root
     :child child
     :result result
     :primary primary
     :unreaped unreaped}))

(defn- ^{:stratum 0} run-operation! [state operation]
  (try+
    (vreset! (:result state) (operation (:root state) (:child state)))
    (catch Object _
      (vreset! (:primary state) (:throwable &throw-context))
      (throw+))))

(defn- ^{:stratum 0} delete! [root] (fs/delete-tree root))

(defn- ^{:stratum 0} confirm-closed! [state worker timeout-ms]
  (when-not (true? (exit/stop! worker timeout-ms))
    (vreset! (:unreaped state) (boundary/unreaped-peer (:root state)))
    (throw+ @(:unreaped state))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} close! [state timeout-ms]
  (when-let [worker (:proc @(:child state))]
    (confirm-closed! state worker timeout-ms))
  (delete! (:root state)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} call!
  "Invoke operation [scratch-path child-volatile], registering its process immediately.
   Caller supplies a protected parent and finite timeout. Return the operation value;
   native cleanup failures throw to the enclosing effect boundary. Unknown termination
   retains scratch and any ordinary operation cause; fatal/interruption priority
   is unchanged. This is for trusted probes, not untrusted workload containment."
  [parent prefix timeout-ms operation]
  (let [state (open-session! parent prefix)]
    (try+
      (cleanup/finish! (partial run-operation! state operation)
                       (partial close! state timeout-ms))
      @(:result state)
      (catch Object _ (failure/propagate! state (:throwable &throw-context))))))

(comment
  (fs/with-temp-dir [parent {:posix-file-permissions directory/private-permissions}]
    (call! parent "miniforge-probe-repl-" 1000 (fn [root _child] (fs/file-name root)))))
