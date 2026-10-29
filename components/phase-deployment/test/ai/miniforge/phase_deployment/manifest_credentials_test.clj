;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.manifest-credentials-test
  (:require [ai.miniforge.phase-deployment.manifest-credentials :as credentials]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} rule
  {:rule/detection {:detector-config {:allow-secret-data? false
                                      :fallback-patterns ["(?i)PRIVATE KEY"]}}})

(deftest ^{:stratum 0} credential-names-end-in-a-credential-term
  (doseq [n ["password" "DB_PASSWORD" "apiKey" "API_KEY" "client-secret" "AWS_SECRET_ACCESS_KEY"
             "auth-token" "passphrase"]]
    (is (credentials/credential-name? n) n))
  (doseq [n ["secretName" "passwordRef" "secretKeyRef" "ACCESS_KEY_ID" "tokenExpirationSeconds"
             "name" "region"]]
    (is (not (credentials/credential-name? n)) n)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} findings [manifest]
  (:findings (credentials/check {:artifact/content manifest} {:policy-pack/rule rule})))

(deftest ^{:stratum 1} secret-data-is-allowed-only-when-the-pack-says-so
  (is (nil? (credentials/check {:artifact/content "kind: Secret\nmetadata:\n  name: db\ndata:\n  password: eA==\n"}
                               {:policy-pack/rule (assoc-in rule [:rule/detection :detector-config
                                                                  :allow-secret-data?] true)}))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} structural-findings-name-the-place-never-the-value
  (testing "Secret data and stringData"
    (is (= ["Secret/db: stringData.password"]
           (findings "kind: Secret\nmetadata:\n  name: db\nstringData:\n  password: x\n"))))
  (testing "a multiword ConfigMap value"
    (is (= ["ConfigMap/cfg: data.password"]
           (findings "kind: ConfigMap\nmetadata:\n  name: cfg\ndata:\n  password: correct horse battery staple\n"))))
  (testing "a credential-named env entry with a literal value, in any workload"
    (is (= ["CronJob/job: env API_KEY"]
           (findings (str "kind: CronJob\nmetadata:\n  name: job\nspec:\n  jobTemplate:\n    spec:\n"
                          "      template:\n        spec:\n          containers:\n            - name: c\n"
                          "              env:\n                - name: API_KEY\n                  value: sk-live-1\n")))))
  (testing "values are never echoed"
    (is (not-any? #(str/includes? % "horse")
                  (findings "kind: ConfigMap\nmetadata:\n  name: cfg\ndata:\n  password: correct horse\n")))))

(deftest ^{:stratum 2} references-and-metadata-are-not-credentials
  (is (empty? (findings (str "kind: Deployment\nmetadata:\n  name: api\n  annotations:\n"
                             "    checksum/secret: 9f86d081884c7d659a2feaa0c55ad015\n"
                             "    nginx.ingress.kubernetes.io/auth-secret: basic-auth\n"
                             "spec:\n  template:\n    spec:\n      containers:\n        - name: api\n"
                             "          env:\n            - name: API_KEY\n              valueFrom:\n"
                             "                secretKeyRef: {name: keys, key: api}\n"
                             "            - name: DB_PASSWORD\n              value: $(SHARED_PASSWORD)\n")))))

(deftest ^{:stratum 2} non-yaml-content-falls-back-to-the-line-patterns
  (is (seq (findings "key: [unclosed\n-----BEGIN PRIVATE KEY-----\n")) "not YAML")
  (is (seq (findings "-----BEGIN PRIVATE KEY----- abc")) "a YAML scalar, not a manifest")
  (is (nil? (findings "key: [unclosed\nplain text\n"))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-deployment.manifest-credentials-test))
