;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.redaction.payment-card-test
  (:require [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} checksum-valid-cards-are-detected-and-redacted-test
  (doseq [card ["4111111111111111" "4111 1111 1111 1111" "4111-1111-1111-1111"
               "378282246310005" "6011111111111117" "500000000009"]]
    (let [text (str "before " card " after")
          value {:nested [text]}
          redacted (redaction/redact value)]
      (is (redaction/payment-card? text))
      (is (redaction/secret-string? text))
      (is (= {:nested ["before [REDACTED] after"]} redacted))
      (is (= redacted (redaction/redact redacted)))
      (is (redaction/clean? redacted)))))

(deftest ^{:stratum 0} invalid-checksums-and-noncard-input-are-preserved-test
  (doseq [value [nil 42 "4111111111111112" "0000000000000000" "123456789012"
                "00000000-0000-0000-0000-000000000042"
                "aaaaaaaa4111111111111111bbbbbbbb"
                "4111 1111 1111 1111 1111"
                "41111111111111111111" "No card here"]]
    (is (false? (redaction/payment-card? value)))
    (is (= value (redaction/redact value)))))

(deftest ^{:stratum 0} numeric-cards-are-redacted-at-original-value-boundaries-test
  (doseq [card [4111111111111111 4111111111111111N 500000000009]]
    (let [value {:nested [card] card :label}
          redacted (redaction/redact value)]
      (is (redaction/payment-card? value))
      (is (= {:nested ["[REDACTED]"] "[REDACTED]" :label} redacted))
      (is (false? (redaction/payment-card? redacted)))
      (is (redaction/clean? redacted))))
  (doseq [value [[4111 1111 1111 1111] 4111111111111112]]
    (is (false? (redaction/payment-card? value)))
    (is (= value (redaction/redact value))))
  (let [value (with-meta [] {:card 4111111111111111})
        redacted (redaction/redact value)]
    (is (redaction/payment-card? value))
    (is (false? (redaction/payment-card? redacted)))
    (is (= {:card "[REDACTED]"} (meta redacted)))))

(deftest ^{:stratum 0} named-card-keys-are-pii-test
  (doseq [key [(keyword "500000000009") (symbol "500000000009")]]
    (let [value {key :label}
          redacted (redaction/redact value)]
      (is (redaction/payment-card? value))
      (is (= {"[REDACTED]" :label} redacted))
      (is (false? (redaction/payment-card? redacted))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.redaction.payment-card-test))
