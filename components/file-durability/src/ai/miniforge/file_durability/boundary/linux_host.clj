;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.linux-host
  "Require a positive native report before considering a non-virtual Linux host."
  (:require [ai.miniforge.file-durability.boundary.native-probe :as probe]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private detection-command
  "The installed native detector must check both VM and container virtualization."
  ["/usr/bin/systemd-detect-virt"])

(def ^{:stratum 0} ^:private no-virtualization
  "systemd reports none with exit 1; errors, other exits and other text are not evidence."
  "none\n")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} non-virtual? []
  (= no-virtualization (probe/read-output detection-command nil #{1})))

(comment (when (= "Linux" (System/getProperty "os.name")) (non-virtual?)))
