;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.redaction.ssn-test
  (:require [clojure.test :refer [deftest is]]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} ssns-are-excluded-from-values-keys-and-metadata
  (doseq [[ssn redacted] [["000-00-0000" "[REDACTED]"]
                         ["employee_ssn_000-00-0000_suffix" "employee_ssn_[REDACTED]_suffix"]]]
    (doseq [value [ssn {:note (str "before " ssn " after")}
                    {ssn :value} {(keyword ssn) :value} (with-meta [] {:note ssn})]]
      (let [clean (redaction/redact value)]
        (is (not (redaction/clean? value)))
        (is (redaction/clean? clean))
        (is (= clean (redaction/redact clean)))))
    (is (= (str "before " redacted " after") (redaction/redact (str "before " ssn " after"))))))

(deftest ^{:stratum 0} longer-digit-groups-are-not-ssns
  (doseq [value ["1000-00-0000" "000-00-00000"]]
    (is (redaction/clean? value))
    (is (= value (redaction/redact value)))))

(comment
  ::ssn-boundaries)
