;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-quantity-test
  (:require [ai.miniforge.phase-opsv.kubernetes-quantity :as quantity]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} oversized-text
  "One character beyond the parser's 128-character work bound."
  (apply str (repeat 129 "1")))

(deftest ^{:stratum 0} equivalent-resource-spellings-compare-without-floating-point-test
  (doseq [[left right] [["1000m" "1"] ["100m" "0.1"] ["1024Mi" "1Gi"]
                         ["1Mi" "1048576"] ["1m" "1000000n"] ["1000u" "1m"]
                         ["1k" "1e3"] ["1E" "1E+18"] ["1Ei" "1152921504606846976"]
                         ["9007199254740993m" "9007199254740.993"]]]
    (is (some? (quantity/value left)))
    (is (= (quantity/value left) (quantity/value right)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} different-or-malformed-values-are-not-rounded-or-accepted-test
  (doseq [[left right] [["1Mi" "1M"] ["9007199254740992" "9007199254740993"]
                         ["1.0000000001" "1"]]]
    (is (not= (quantity/value left) (quantity/value right))))
  (doseq [invalid [nil 1 "" "-1" "NaN" "1mGi" "1e3m" "1e999999999" "1e99999999999999999999999"
                    oversized-text]]
    (is (nil? (quantity/value invalid)))))
