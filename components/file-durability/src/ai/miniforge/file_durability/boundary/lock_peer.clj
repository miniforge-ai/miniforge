;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.lock-peer
  "Class 1 peer entry point; only exit status and private probe markers leave the process."
  (:require [ai.miniforge.file-durability.boundary.lock-peer-session :as session]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} -main [& arguments]
  (let [[expected-runtime location] arguments
        complete? (and (= 2 (count arguments)) (session/completed? expected-runtime location))]
    (System/exit (if complete? 0 1))))

(comment (session/completed? "unsupported" "."))
