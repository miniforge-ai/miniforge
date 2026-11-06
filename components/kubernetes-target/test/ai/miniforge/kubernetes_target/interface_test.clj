;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.kubernetes-target.interface-test
  (:require [ai.miniforge.kubernetes-target.fixtures :as fixtures]
            [ai.miniforge.kubernetes-target.interface :as target]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} closed-identity-declaration-is-not-a-resource-inventory-test
  (let [declared (fixtures/target)]
    (is (m/validate target/Target declared))
    (is (not (m/validate target/Target (assoc declared :observed? true))))
    (doseq [invalid [nil [] "staging"]]
      (is (not (m/validate target/Target invalid))))))

(deftest ^{:stratum 0} every-native-identity-binding-remains-required-test
  (let [declared (fixtures/target)]
    (doseq [field (keys declared)]
      (is (not (m/validate target/Target (dissoc declared field))))
      (doseq [invalid [nil "" " \t\n" false 0]]
        (is (not (m/validate target/Target (assoc declared field invalid))))))))

(deftest ^{:stratum 0} extraction-preserves-existing-whitespace-admission-test
  (let [declared (fixtures/target)]
    (doseq [text [" name " "\u00a0" "\u2007" "\u202f"]]
      (is (m/validate target/IdentityText text))
      (doseq [field (keys declared)]
        (is (m/validate target/Target (assoc declared field text)))))
    (doseq [text ["\u2003" "\t\n" ""]]
      (is (not (m/validate target/IdentityText text))))))

(comment (clojure.test/run-tests 'ai.miniforge.kubernetes-target.interface-test))
