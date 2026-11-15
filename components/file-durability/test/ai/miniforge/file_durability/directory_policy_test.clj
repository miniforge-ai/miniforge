;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.directory-policy-test
  (:require [ai.miniforge.file-durability.boundary.directory-policy :as policy]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} root-account "root")

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} root-identity-does-not-create-duplicate-owner-errors
  (let [original (System/getProperty "user.name")]
    (try
      (System/setProperty "user.name" root-account)
      (is (true? (#'policy/trusted-owner? root-account)))
      (finally
        (if original (System/setProperty "user.name" original) (System/clearProperty "user.name"))))))

(comment ::directory-owner-policy)
