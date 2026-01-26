(ns ai.miniforge.knowledge.promotion-test
  "Tests for pack promotion with justification tracking."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.knowledge.promotion :as promotion]))

;------------------------------------------------------------------------------ Layer 0
;; Justification Formatting Tests

(deftest test-format-justification-basic
  (testing "Format justification from template"
    (is (= "passed knowledge-safety scans with no violations"
           (promotion/format-justification :safety-scan-passed)))

    (is (= "manual review approved by trusted administrator"
           (promotion/format-justification :manual-review-approved)))

    (is (= "verified signature from trusted key"
           (promotion/format-justification :signature-verified)))))

(deftest test-format-justification-with-details
  (testing "Format justification with additional details"
    (is (= "passed knowledge-safety scans with no violations (3 scans completed)"
           (promotion/format-justification :safety-scan-passed "3 scans completed")))

    (is (= "manual review approved by trusted administrator (reviewed by 3 engineers)"
           (promotion/format-justification :manual-review-approved
                                           "reviewed by 3 engineers")))))

(deftest test-format-justification-unknown-template
  (testing "Unknown template keys generate fallback justification"
    (let [result (promotion/format-justification :custom-reason)]
      (is (string? result))
      (is (re-find #"custom-reason" result)))))

;------------------------------------------------------------------------------ Layer 1
;; Promotion Record Creation Tests

(deftest test-create-promotion-record-minimal
  (testing "Create promotion record with minimal required fields"
    (let [record (promotion/create-promotion-record
                  "test-pack-001"
                  :untrusted
                  :trusted
                  "passed knowledge-safety scans")]

      (is (= "test-pack-001" (:pack/id record)))
      (is (= :untrusted (:from-trust record)))
      (is (= :trusted (:to-trust record)))
      (is (= "passed knowledge-safety scans" (:promotion-justification record)))
      (is (= :knowledge (:pack/type record)))
      (is (= "system" (:promoted-by record)))
      (is (= "knowledge-safety" (:promotion-policy record)))
      (is (inst? (:promoted-at record))))))

(deftest test-create-promotion-record-full
  (testing "Create promotion record with all fields"
    (let [record (promotion/create-promotion-record
                  "security-pack-v1"
                  :untrusted
                  :trusted
                  "manual review approved"
                  :pack-type :policy
                  :promoted-by "admin@example.com"
                  :promotion-policy "manual-review"
                  :pack-hash "sha256:abc123def456"
                  :pack-signature "sig789")]

      (is (= "security-pack-v1" (:pack/id record)))
      (is (= :policy (:pack/type record)))
      (is (= :untrusted (:from-trust record)))
      (is (= :trusted (:to-trust record)))
      (is (= "admin@example.com" (:promoted-by record)))
      (is (= "manual-review" (:promotion-policy record)))
      (is (= "manual review approved" (:promotion-justification record)))
      (is (= "sha256:abc123def456" (:pack-hash record)))
      (is (= "sig789" (:pack-signature record))))))

(deftest test-create-promotion-record-requires-justification
  (testing "Promotion record requires non-empty justification"
    (is (thrown? AssertionError
                 (promotion/create-promotion-record
                  "test-pack"
                  :untrusted
                  :trusted
                  "")))  ;; Empty justification should fail precondition

    (is (thrown? AssertionError
                 (promotion/create-promotion-record
                  "test-pack"
                  :untrusted
                  :trusted
                  nil)))))  ;; Nil justification should fail precondition

;------------------------------------------------------------------------------ Layer 2
;; Trust Level Validation Tests

(deftest test-valid-promotion-paths
  (testing "Valid promotion paths are accepted"
    (is (true? (promotion/valid-promotion? :untrusted :trusted))
        "untrusted -> trusted is most common promotion")

    (is (true? (promotion/valid-promotion? :untrusted :tainted))
        "untrusted -> tainted marks problematic content")

    (is (true? (promotion/valid-promotion? :tainted :untrusted))
        "tainted -> untrusted cleans up content")

    (is (true? (promotion/valid-promotion? :trusted :untrusted))
        "trusted -> untrusted demotes compromised content")))

(deftest test-invalid-promotion-paths
  (testing "Invalid promotion paths are rejected"
    (is (false? (promotion/valid-promotion? :tainted :trusted))
        "Cannot promote directly from tainted to trusted")

    (is (false? (promotion/valid-promotion? :invalid :trusted))
        "Invalid trust levels are rejected")

    (is (false? (promotion/valid-promotion? :untrusted :invalid))
        "Invalid trust levels are rejected")))

(deftest test-validate-promotion-success
  (testing "Valid promotion record passes validation"
    (let [record (promotion/create-promotion-record
                  "test-pack"
                  :untrusted
                  :trusted
                  "passed scans")
          result (promotion/validate-promotion record)]

      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest test-validate-promotion-invalid-path
  (testing "Invalid promotion path fails validation"
    (let [record (promotion/create-promotion-record
                  "test-pack"
                  :tainted
                  :trusted
                  "attempted invalid promotion")
          result (promotion/validate-promotion record)]

      (is (not (:valid? result)))
      (is (some #(re-find #"Invalid promotion path" %) (:errors result))))))

;------------------------------------------------------------------------------ Layer 3
;; Promotion Execution Tests

(deftest test-promote-pack-success
  (testing "Successful pack promotion returns valid record"
    (let [result (promotion/promote-pack
                  "my-pack-v1"
                  :untrusted
                  :trusted
                  (promotion/format-justification :safety-scan-passed)
                  :promoted-by "admin"
                  :pack-hash "sha256:test123")]

      (is (:valid? result))
      (is (empty? (:errors result)))
      (is (map? (:promotion-record result)))
      (is (= "my-pack-v1" (-> result :promotion-record :pack/id)))
      (is (= "passed knowledge-safety scans with no violations"
             (-> result :promotion-record :promotion-justification))))))

(deftest test-promote-pack-invalid-path
  (testing "Invalid promotion path returns errors"
    (let [result (promotion/promote-pack
                  "bad-pack"
                  :tainted
                  :trusted
                  "attempting invalid promotion")]

      (is (not (:valid? result)))
      (is (seq (:errors result)))
      (is (map? (:promotion-record result))
          "Should still return promotion record for inspection"))))

;------------------------------------------------------------------------------ Layer 4
;; Workflow Integration Tests

(deftest test-record-promotion-in-workflow-empty
  (testing "Record promotion in empty workflow state"
    (let [workflow-state {}
          promotion-record (promotion/create-promotion-record
                            "pack-001"
                            :untrusted
                            :trusted
                            "passed scans")
          updated-state (promotion/record-promotion-in-workflow
                         workflow-state
                         promotion-record)]

      (is (vector? (:workflow/pack-promotions updated-state)))
      (is (= 1 (count (:workflow/pack-promotions updated-state))))
      (is (= "pack-001"
             (-> updated-state
                 :workflow/pack-promotions
                 first
                 :pack/id))))))

(deftest test-record-promotion-in-workflow-multiple
  (testing "Record multiple promotions in workflow state"
    (let [workflow-state {}
          promotion1 (promotion/create-promotion-record
                      "pack-001" :untrusted :trusted "scan 1")
          promotion2 (promotion/create-promotion-record
                      "pack-002" :untrusted :trusted "scan 2")
          promotion3 (promotion/create-promotion-record
                      "pack-003" :untrusted :trusted "scan 3")

          state1 (promotion/record-promotion-in-workflow workflow-state promotion1)
          state2 (promotion/record-promotion-in-workflow state1 promotion2)
          state3 (promotion/record-promotion-in-workflow state2 promotion3)]

      (is (= 3 (count (:workflow/pack-promotions state3))))
      (is (= ["pack-001" "pack-002" "pack-003"]
             (map :pack/id (:workflow/pack-promotions state3)))))))

(deftest test-record-promotion-preserves-other-fields
  (testing "Recording promotion preserves other workflow state fields"
    (let [workflow-state {:workflow/status :in-progress
                          :workflow/id (random-uuid)
                          :workflow/other-data "preserved"}
          promotion-record (promotion/create-promotion-record
                            "pack-001" :untrusted :trusted "test")
          updated-state (promotion/record-promotion-in-workflow
                         workflow-state
                         promotion-record)]

      (is (= :in-progress (:workflow/status updated-state)))
      (is (= (:workflow/id workflow-state) (:workflow/id updated-state)))
      (is (= "preserved" (:workflow/other-data updated-state)))
      (is (= 1 (count (:workflow/pack-promotions updated-state)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run all tests
  (clojure.test/run-tests)

  ;; Run specific test
  (test-promote-pack-success)

  :leave-this-here)
