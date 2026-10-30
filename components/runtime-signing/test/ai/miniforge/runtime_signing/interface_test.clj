;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.interface-test
  (:require
   [ai.miniforge.runtime-signing.interface :as signing]
   [clojure.test :refer [deftest is]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} grant
  {:grant/id #uuid "00000000-0000-0000-0000-000000000001"
   :grant/scope {:pr/repo "miniforge-ai/miniforge" :pr/number 7}
   :grant/expires-at (Instant/parse "2026-10-29T00:15:00Z")})

(defn- ^{:stratum 0} random-key []
  (let [key-bytes (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) key-bytes)
    key-bytes))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} signatures-bind-the-exact-value-test
  (let [k (random-key)
        signature (signing/sign k grant)]
    (is (signing/signed? k grant signature))
    (is (signing/signed? k (into {} (reverse (seq grant))) signature)
        "key order does not change the canonical value")
    (is (not (signing/signed? k (assoc-in grant [:grant/scope :pr/number] 8) signature)))
    (is (not (signing/signed? (random-key) grant signature))
        "another runtime's key does not verify")
    (is (not (signing/signed? k grant nil)))
    (is (not (signing/signed? k grant (apply str (repeat 64 "0")))))))
