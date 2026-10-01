;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.redaction.payment-card
  "Checksum-aware detection shared by evidence scanning and redaction."
  (:require [ai.miniforge.redaction.policy :as policy]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-digit [index digit]
  (let [weighted (if (odd? index) (* 2 digit) digit)]
    (+ (quot weighted 10) (mod weighted 10))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} valid-checksum? [candidate]
  (let [digits (map #(Character/digit ^char % 10) (str/replace candidate #"[^0-9]" ""))]
    (and (some pos? digits)
         (zero? (mod (reduce + (map-indexed check-digit (reverse digits))) 10)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} present? [text]
  (boolean (and (string? text)
                (some valid-checksum? (re-seq (:redaction/payment-card-pattern @policy/policy) text)))))

(defn ^{:stratum 2} redact [text marker]
  (str/replace text (:redaction/payment-card-pattern @policy/policy)
               #(if (valid-checksum? %) marker %)))

(comment
  (present? "4111 1111 1111 1111"))
