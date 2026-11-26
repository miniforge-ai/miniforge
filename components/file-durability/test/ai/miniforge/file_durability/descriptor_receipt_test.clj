;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.descriptor-receipt-test
  (:require [ai.miniforge.file-durability.boundary.descriptor-receipt :as receipt]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} invalid-receipts
  "Reject truncated, signed, overflowing, padded and additional native output."
  ["" "1 2" "-1 2\n" "1 -2\n" "1 2\nextra" " 1 2\n"
   "18446744073709551615 2\n" "1 18446744073709551615\n"
   "111111111111111111111 2\n" "1 2\r\n"])

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-descriptor-receipts-have-one-canonical-identity
  (is (= {:storage/device-number 1 :storage/inode 2} (receipt/decode "1 2\n")))
  (doseq [text invalid-receipts] (is (nil? (receipt/decode text)))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.descriptor-receipt-test))
