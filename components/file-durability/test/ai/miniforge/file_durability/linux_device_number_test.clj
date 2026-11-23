;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.linux-device-number-test
  (:require [ai.miniforge.file-durability.boundary.linux-device-number :as device]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-kernel-device-format-covers-extended-major-and-minor-fields
  (doseq [[kernel encoded] [["0:0" 0] ["8:0" 2048] ["259:0" 66304]
                            ["0:256" 1048576] ["4095:1048575" 4294967295]]]
    (is (true? (device/matches? kernel encoded)))))

(deftest ^{:stratum 0} test-mismatched-or-unsupported-device-encodings-refuse
  (doseq [[kernel encoded] [["8:0" 41] ["08:0" 2048] ["0:0" -1] ["0:0" 4294967296]]]
    (is (false? (device/matches? kernel encoded)))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.linux-device-number-test))
