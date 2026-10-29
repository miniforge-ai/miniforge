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
  (testing "a plaintext secret in the manifests denies authority"
    (let [record (prepared (str "kind: ConfigMap\ndata:\n" secret-line))]
      (is (not (authority/permitted? record)))
      (is (= [:deploy/no-secrets-in-manifests]
             (mapv :rule-id (get-in record [:authority/policy :blocking])))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-deployment.deploy-manifest-policy-test))
