;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.native-probe-test
  (:require [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [clojure.test :refer [deftest is]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} excessive-output-bytes
  "One byte beyond the process boundary's accepted payload."
  65537)

(deftest ^{:stratum 0} small-native-output-is-read-with-utf8
  (let [payload "clé"
        bytes (.getBytes payload StandardCharsets/UTF_8)]
    (is (= payload (probe/read-utf8 (ByteArrayInputStream. bytes))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} oversized-native-output-is-refused
  (is (nil? (probe/read-utf8 (ByteArrayInputStream. (byte-array excessive-output-bytes))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.native-probe-test))
