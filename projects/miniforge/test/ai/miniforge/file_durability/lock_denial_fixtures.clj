;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.lock-denial-fixtures
  "Native fault peers; production marker and lock policy remains unchanged."
  (:require [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} scenarios
  "Fault peers speak the marker protocol but cannot prove mutual exclusion."
  {:premature-exit "exit 0"
   :timeout "exec sleep 30"
   :false-lock "touch \"$1/peer-excluded\"; while ! test -f \"$1/host-released\"; do sleep 0.01; done; touch \"$1/peer-holding\"; while ! test -f \"$1/host-observed-exclusion\"; do sleep 0.01; done"})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} launch! [mode observed root _configuration]
  (let [child (process/process ["/bin/sh" "-c" (get scenarios mode) "lock-denial-fixture" (str root)])]
    (vreset! observed [child root])
    child))

(comment (keys scenarios))
