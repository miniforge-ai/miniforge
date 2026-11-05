;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.gate.policy-pack-structured-artifact-test
  "The :policy-pack gate applies phase-scoped rules to a structured artifact
   in the phase the workflow lifecycle is running."
  (:require [ai.miniforge.gate.policy :as policy]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} destroy-pack
  "The shape of the deployment-safety pack's destroy rule."
  {:pack/id "test-deploy-safety"
   :pack/version "2026.10.29"
   :pack/rules [{:rule/id :no-destroy
                 :rule/severity :critical
                 :rule/applies-to {:phases #{:provision}}
                 :rule/detection {:type :content-scan :patterns ["\"delete\""]}
                 :rule/enforcement {:action :hard-halt :message "destructive change"}}]})

(def ^{:stratum 0} parsed-preview
  "A parsed `pulumi preview --json` deleting a production database."
  {:steps [{:op "delete"
            :urn "urn:pulumi:prod::infra::gcp:sql/databaseInstance:DatabaseInstance::prod-db"}]
   :changeSummary {:delete 1}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-parsed-preview-is-scanned-in-the-running-phase
  (doseq [phase-ctx [{:phase {:name :provision :status :pending-gates}}
                     {:phase :provision}
                     {:execution/current-phase :provision}]]
    (testing (pr-str phase-ctx)
      (let [result (policy/check-policy-pack parsed-preview
                                             (assoc phase-ctx :policy-packs [destroy-pack]))]
        (is (false? (:passed? result)))
        (is (= [:no-destroy] (mapv :code (:errors result))))))))

(deftest ^{:stratum 1} a-rule-scoped-to-another-phase-does-not-apply
  (let [result (policy/check-policy-pack parsed-preview
                                         {:policy-packs [destroy-pack]
                                          :phase {:name :deploy}})]
    (is (true? (:passed? result)))))

(comment
  (clojure.test/run-tests 'ai.miniforge.gate.policy-pack-structured-artifact-test))
