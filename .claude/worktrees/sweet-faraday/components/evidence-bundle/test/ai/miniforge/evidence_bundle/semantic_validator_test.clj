(ns ai.miniforge.evidence-bundle.semantic-validator-test
  "Tests for semantic validation logic."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.protocols.impl.semantic-validator :as sem-val]))

;------------------------------------------------------------------------------ Terraform Plan Analysis

(deftest analyze-terraform-plan-test
  (testing "Counts resource creates"
    (let [plan-artifact {:artifact/type :terraform-plan
                        :artifact/content "aws_instance.web will be created\naws_s3_bucket.data will be created"}
          result (sem-val/analyze-terraform-plan-impl plan-artifact)]
      (is (= 2 (:creates result)))
      (is (= 0 (:updates result)))
      (is (= 0 (:destroys result)))))

  (testing "Counts resource updates"
    (let [plan-artifact {:artifact/type :terraform-plan
                        :artifact/content "aws_instance.web will be updated in-place"}
          result (sem-val/analyze-terraform-plan-impl plan-artifact)]
      (is (= 0 (:creates result)))
      (is (= 1 (:updates result)))
      (is (= 0 (:destroys result)))))

  (testing "Counts resource destroys"
    (let [plan-artifact {:artifact/type :terraform-plan
                        :artifact/content "aws_instance.web will be destroyed"}
          result (sem-val/analyze-terraform-plan-impl plan-artifact)]
      (is (= 0 (:creates result)))
      (is (= 0 (:updates result)))
      (is (= 1 (:destroys result)))))

  (testing "Counts resource recreates as create + destroy"
    (let [plan-artifact {:artifact/type :terraform-plan
                        :artifact/content "aws_instance.web must be replaced"}
          result (sem-val/analyze-terraform-plan-impl plan-artifact)]
      (is (= 1 (:creates result)))
      (is (= 0 (:updates result)))
      (is (= 1 (:destroys result)))))

  (testing "Handles mixed changes"
    (let [plan-artifact {:artifact/type :terraform-plan
                        :artifact/content
                        (str "aws_instance.web will be created\n"
                             "aws_s3_bucket.data will be updated in-place\n"
                             "aws_db_instance.old will be destroyed")}
          result (sem-val/analyze-terraform-plan-impl plan-artifact)]
      (is (= 1 (:creates result)))
      (is (= 1 (:updates result)))
      (is (= 1 (:destroys result))))))

;------------------------------------------------------------------------------ Kubernetes Manifest Analysis

(deftest analyze-kubernetes-manifest-test
  (testing "Counts resources in manifest"
    (let [manifest-artifact {:artifact/type :kubernetes-manifest
                            :artifact/content
                            (str "kind: Deployment\n"
                                 "kind: Service\n"
                                 "kind: ConfigMap")}
          result (sem-val/analyze-kubernetes-manifest-impl manifest-artifact)]
      (is (= 3 (:creates result))))))

;------------------------------------------------------------------------------ Intent Validation

(deftest validate-intent-import-test
  (testing "Import intent passes with no changes"
    (let [intent {:intent/type :import}
          artifacts []
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (:passed? result))
      (is (empty? (:violations result)))
      (is (= :import (:semantic-validation/declared-intent result)))
      (is (= :import (:semantic-validation/actual-behavior result)))))

  (testing "Import intent fails with creates"
    (let [intent {:intent/type :import}
          artifacts [{:artifact/type :terraform-plan
                      :artifact/content "aws_instance.web will be created"}]
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (not (:passed? result)))
      (is (seq (:violations result)))
      (is (= 1 (:semantic-validation/resource-creates result)))
      (is (= :create (:semantic-validation/actual-behavior result))))))

(deftest validate-intent-create-test
  (testing "Create intent passes with creates"
    (let [intent {:intent/type :create}
          artifacts [{:artifact/type :terraform-plan
                      :artifact/content "aws_instance.web will be created"}]
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (:passed? result))
      (is (empty? (:violations result)))
      (is (= 1 (:semantic-validation/resource-creates result)))))

  (testing "Create intent fails without creates"
    (let [intent {:intent/type :create}
          artifacts []
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (not (:passed? result)))
      (is (seq (:violations result)))
      (is (= 0 (:semantic-validation/resource-creates result))))))

(deftest validate-intent-update-test
  (testing "Update intent passes with updates only"
    (let [intent {:intent/type :update}
          artifacts [{:artifact/type :terraform-plan
                      :artifact/content "aws_instance.web will be updated in-place"}]
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (:passed? result))
      (is (= 1 (:semantic-validation/resource-updates result)))))

  (testing "Update intent fails with creates"
    (let [intent {:intent/type :update}
          artifacts [{:artifact/type :terraform-plan
                      :artifact/content "aws_instance.web will be created"}]
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (not (:passed? result)))
      (is (seq (:violations result))))))

(deftest validate-intent-destroy-test
  (testing "Destroy intent passes with destroys"
    (let [intent {:intent/type :destroy}
          artifacts [{:artifact/type :terraform-plan
                      :artifact/content "aws_instance.web will be destroyed"}]
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (:passed? result))
      (is (= 1 (:semantic-validation/resource-destroys result)))))

  (testing "Destroy intent fails with creates"
    (let [intent {:intent/type :destroy}
          artifacts [{:artifact/type :terraform-plan
                      :artifact/content "aws_instance.web will be created"}]
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (not (:passed? result))))))

(deftest validate-intent-migrate-test
  (testing "Migrate intent passes with creates and destroys"
    (let [intent {:intent/type :migrate}
          artifacts [{:artifact/type :terraform-plan
                      :artifact/content
                      (str "aws_instance.old will be destroyed\n"
                           "aws_instance.new will be created")}]
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (:passed? result))
      (is (= 1 (:semantic-validation/resource-creates result)))
      (is (= 1 (:semantic-validation/resource-destroys result)))))

  (testing "Migrate intent fails without creates"
    (let [intent {:intent/type :migrate}
          artifacts [{:artifact/type :terraform-plan
                      :artifact/content "aws_instance.old will be destroyed"}]
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (not (:passed? result))))))

(deftest validate-intent-refactor-test
  (testing "Refactor intent passes with no resource changes"
    (let [intent {:intent/type :refactor}
          artifacts []
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (:passed? result))
      (is (= 0 (:semantic-validation/resource-creates result)))
      (is (= 0 (:semantic-validation/resource-updates result)))
      (is (= 0 (:semantic-validation/resource-destroys result)))))

  (testing "Refactor intent fails with creates"
    (let [intent {:intent/type :refactor}
          artifacts [{:artifact/type :terraform-plan
                      :artifact/content "aws_instance.web will be created"}]
          result (sem-val/validate-intent-impl intent artifacts)]
      (is (not (:passed? result))))))
