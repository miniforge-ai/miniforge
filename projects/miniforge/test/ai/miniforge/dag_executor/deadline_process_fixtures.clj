;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.deadline-process-fixtures
  "Observe actual native readers and their cleanup without replacing capture policy."
  (:require [ai.miniforge.subprocess.interface :as subprocess]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} long-lived-command
  "A disposable local child that remains alive until owned-process cleanup."
  ["/bin/sh" "-c" "exec sleep 30"])

(defn ^{:stratum 0} record-drain! [handles stream options]
  (let [handle (subprocess/drain-stream stream options)]
    (swap! handles conj handle)
    handle))

(defn ^{:stratum 0} record-cancel! [cancelled handle]
  (swap! cancelled conj handle)
  (subprocess/cancel-drain! handle))

(comment long-lived-command)
