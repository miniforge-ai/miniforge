;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.deploy-manifest-policy-test
  "Deploy authority evaluates the deployment pack's :deploy rules against the
   rendered manifests before anything is applied."
  (:require [ai.miniforge.phase-deployment.deploy-authority :as authority]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} target
  {:kustomize-dir "/repo/k8s/overlays/prod" :namespace "prod" :context "gke-prod"
   :app-label "api" :deployment-name "api" :phase-config {}})

(def ^{:stratum 0} secret-line "  api_key: \"sk-live-0123456789abcdef\"\n")

(def ^{:stratum 0} plain-secret-lines
  "A plain-scalar secret beside reference keys that are not secrets."
  "  passwordRef: db-credentials\n  secretName: api-tls\n  password: correct-horse-battery-staple\n")

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} prepared [rendered-yaml]
  (fs/with-temp-dir [dir {:prefix "deploy-authority-"}]
    (authority/prepare {:execution/id (random-uuid)
                        :execution/phase-results {:provision {:result {:output {:steps []}}}}
                        :grant-breach-dir (str dir)}
                       (random-uuid) target
                       {:app-label "api" :rendered-yaml rendered-yaml}
                       (Instant/parse "2026-10-29T00:00:00Z"))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-manifest-secret-blocks-deploy-authority
  (testing "clean manifests are authorized"
    (is (authority/permitted? (prepared "kind: ConfigMap\ndata:\n  region: us-east1\n"))))
  (testing "reference keys such as passwordRef: and secretName: are not secrets"
    (is (authority/permitted?
         (prepared "kind: Deployment\nspec:\n  passwordRef: db-credentials\n  secretName: api-tls\n"))))
  (doseq [secret [secret-line plain-secret-lines]]
    (testing "a quoted or plain-scalar secret in the manifests denies authority"
      (let [record (prepared (str "kind: ConfigMap\ndata:\n" secret))]
        (is (not (authority/permitted? record)))
        (is (= [:deploy/no-secrets-in-manifests]
               (mapv :rule-id (get-in record [:authority/policy :blocking]))))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-deployment.deploy-manifest-policy-test))
