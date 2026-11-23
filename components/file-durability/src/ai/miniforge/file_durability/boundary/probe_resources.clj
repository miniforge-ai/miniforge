;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.probe-resources
  "Own private scratch until the registered native process snapshot is confirmed dead."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.directory-policy :as directory]
            [ai.miniforge.file-durability.boundary.native-process-exit :as exit]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-operation! [result operation root child]
  (vreset! result (operation root child)))

(defn- ^{:stratum 0} delete! [root] (fs/delete-tree root))

(defn- ^{:stratum 0} confirm-closed! [worker timeout-ms root]
  (when-not (true? (exit/stop! worker timeout-ms))
    (throw+ (boundary/unreaped-peer root))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} close! [root child timeout-ms]
  (when-let [worker (:proc @child)]
    (confirm-closed! worker timeout-ms root))
  (delete! root))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} call!
  "Invoke operation [scratch-path child-volatile], registering its process immediately.
   Caller supplies a protected parent and finite timeout. Return the operation value;
   native cleanup failures throw to the enclosing effect boundary. Unknown termination
   retains scratch. This is for trusted probes, not untrusted workload containment."
  [parent prefix timeout-ms operation]
  (let [root (fs/create-temp-dir {:dir parent
                                 :prefix prefix
                                 :posix-file-permissions directory/private-permissions})
        child (volatile! nil)
        result (volatile! nil)]
    (cleanup/finish! (partial run-operation! result operation root child)
                     (partial close! root child timeout-ms))
    @result))

(comment
  (fs/with-temp-dir [parent {:posix-file-permissions directory/private-permissions}]
    (call! parent "miniforge-probe-repl-" 1000 (fn [root _child] (fs/file-name root)))))
