(ns ai.miniforge.data-foundry.connector.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.data-foundry.connector.interface :as conn]
            [ai.miniforge.data-foundry.connector.messages :as msg]))

(deftest connector-types-test
  (testing "N2 §1 connector types"
    (is (= #{:source :sink :bidirectional} conn/connector-types))))

(deftest connector-capabilities-test
  (testing "N2 §1.4 capabilities"
    (is (contains? conn/connector-capabilities :cap/discovery))
    (is (contains? conn/connector-capabilities :cap/incremental))
    (is (contains? conn/connector-capabilities :cap/batch))
    (is (contains? conn/connector-capabilities :cap/upsert))
    (is (contains? conn/connector-capabilities :cap/transactions))
    (is (contains? conn/connector-capabilities :cap/rate-limiting))
    (is (contains? conn/connector-capabilities :cap/pagination))))

;; ---------------------------------------------------------------------------
;; retry-policy presets
;; ---------------------------------------------------------------------------

(deftest retry-policy-default-test
  (testing ":default preset is exponential-backoff with 3 attempts"
    (let [policy (conn/retry-policy :default)]
      (is (= :exponential-backoff (:retry/strategy policy)))
      (is (= 3 (:retry/max-attempts policy)))
      (is (pos? (:retry/base-delay-ms policy))))))

(deftest retry-policy-none-test
  (testing ":none preset disables retries"
    (let [policy (conn/retry-policy :none)]
      (is (= :none (:retry/strategy policy)))
      (is (= 1 (:retry/max-attempts policy))))))

(deftest retry-policy-unknown-test
  (testing "unknown preset throws ex-info with known-keys context"
    (try
      (conn/retry-policy :nope)
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :nope (:policy-key data)))
          (is (contains? (:known-keys data) :default))
          (is (contains? (:known-keys data) :none)))))))

(deftest retry-policy-presets-pass-validation-test
  (testing "Each preset, when used in a connector registration, validates clean"
    (let [base {:connector/id (java.util.UUID/randomUUID)
                :connector/name "Preset Test"
                :connector/type :source
                :connector/version "1.0.0"
                :connector/capabilities #{:cap/batch}
                :connector/auth-methods #{:none}
                :connector/maintainer "test-team"}]
      (doseq [preset-key (keys conn/retry-policies)]
        (let [connector (assoc base
                               :connector/retry-policy (conn/retry-policy preset-key))
              result (conn/validate-connector connector)]
          (is (:success? result)
              (str "preset " preset-key " should validate")))))))

;; ---------------------------------------------------------------------------
;; validate-connector
;; ---------------------------------------------------------------------------

(def ^:private valid-connector
  {:connector/id (java.util.UUID/randomUUID)
   :connector/name "Test Source"
   :connector/type :source
   :connector/version "1.0.0"
   :connector/capabilities #{:cap/discovery :cap/batch}
   :connector/auth-methods #{:api-key}
   :connector/retry-policy {:retry/strategy :exponential
                            :retry/max-attempts 3
                            :retry/initial-delay-ms 1000}
   :connector/maintainer "test-team"})

(deftest validate-connector-happy-test
  (testing "Fully valid connector passes validation"
    (let [result (conn/validate-connector valid-connector)]
      (is (:success? result))
      (is (nil? (:errors result))))))

(deftest validate-connector-missing-id-test
  (testing "Missing :connector/id"
    (let [result (conn/validate-connector (dissoc valid-connector :connector/id))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/id-required) %) (:errors result))))))

(deftest validate-connector-missing-name-test
  (testing "Missing :connector/name"
    (let [result (conn/validate-connector (dissoc valid-connector :connector/name))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/name-must-be-string) %) (:errors result))))))

(deftest validate-connector-invalid-name-test
  (testing "Non-string :connector/name"
    (let [result (conn/validate-connector (assoc valid-connector :connector/name 42))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/name-must-be-string) %) (:errors result))))))

(deftest validate-connector-invalid-type-test
  (testing "Invalid :connector/type"
    (let [result (conn/validate-connector (assoc valid-connector :connector/type :invalid))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/type-invalid {:allowed conn/connector-types}) %)
                (:errors result))))))

(deftest validate-connector-missing-type-test
  (testing "Missing :connector/type"
    (let [result (conn/validate-connector (dissoc valid-connector :connector/type))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/type-invalid {:allowed conn/connector-types}) %)
                (:errors result))))))

(deftest validate-connector-missing-version-test
  (testing "Missing :connector/version"
    (let [result (conn/validate-connector (dissoc valid-connector :connector/version))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/version-must-be-string) %) (:errors result))))))

(deftest validate-connector-invalid-version-test
  (testing "Non-string :connector/version"
    (let [result (conn/validate-connector (assoc valid-connector :connector/version 123))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/version-must-be-string) %) (:errors result))))))

(deftest validate-connector-missing-auth-methods-test
  (testing "Missing :connector/auth-methods"
    (let [result (conn/validate-connector (dissoc valid-connector :connector/auth-methods))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/auth-methods-must-be-set) %) (:errors result))))))

(deftest validate-connector-empty-auth-methods-test
  (testing "Empty :connector/auth-methods"
    (let [result (conn/validate-connector (assoc valid-connector :connector/auth-methods #{}))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/auth-methods-empty) %) (:errors result))))))

(deftest validate-connector-non-set-auth-methods-test
  (testing "Non-set :connector/auth-methods"
    (let [result (conn/validate-connector (assoc valid-connector :connector/auth-methods [:api-key]))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/auth-methods-must-be-set) %) (:errors result))))))

(deftest validate-connector-missing-maintainer-test
  (testing "Missing :connector/maintainer"
    (let [result (conn/validate-connector (dissoc valid-connector :connector/maintainer))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/maintainer-must-be-string) %) (:errors result))))))

(deftest validate-connector-non-string-maintainer-test
  (testing "Non-string :connector/maintainer"
    (let [result (conn/validate-connector (assoc valid-connector :connector/maintainer :team))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/maintainer-must-be-string) %) (:errors result))))))

(deftest validate-connector-missing-capabilities-test
  (testing "Missing :connector/capabilities"
    (let [result (conn/validate-connector (dissoc valid-connector :connector/capabilities))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/capabilities-must-be-set) %) (:errors result))))))

(deftest validate-connector-empty-capabilities-test
  (testing "Empty :connector/capabilities"
    (let [result (conn/validate-connector (assoc valid-connector :connector/capabilities #{}))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/capabilities-empty) %) (:errors result))))))

(deftest validate-connector-missing-retry-policy-test
  (testing "Missing :connector/retry-policy"
    (let [result (conn/validate-connector (dissoc valid-connector :connector/retry-policy))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/retry-policy-required) %) (:errors result))))))

(deftest validate-connector-retry-policy-missing-strategy-test
  (testing "Retry policy without :retry/strategy"
    (let [result (conn/validate-connector
                  (assoc valid-connector :connector/retry-policy
                         {:retry/max-attempts 3}))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/retry-strategy-required) %) (:errors result))))))

(deftest validate-connector-retry-policy-missing-max-attempts-test
  (testing "Retry policy without :retry/max-attempts"
    (let [result (conn/validate-connector
                  (assoc valid-connector :connector/retry-policy
                         {:retry/strategy :exponential}))]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/retry-max-attempts-required) %) (:errors result))))))

(deftest validate-connector-multiple-errors-test
  (testing "Multiple validation errors accumulate"
    (let [result (conn/validate-connector {})]
      (is (not (:success? result)))
      (is (> (count (:errors result)) 1))
      ;; Should report id, name, type, version, capabilities, auth-methods,
      ;; retry-policy, and maintainer errors
      (is (>= (count (:errors result)) 8)))))

;; ---------------------------------------------------------------------------
;; create-connector
;; ---------------------------------------------------------------------------

(deftest create-connector-test
  (testing "SC-01: Create valid source connector"
    (let [result (conn/create-connector
                  {:connector/name "Test Source"
                   :connector/type :source
                   :connector/version "1.0.0"
                   :connector/capabilities #{:cap/discovery :cap/batch}
                   :connector/auth-methods #{:api-key}
                   :connector/retry-policy {:retry/strategy :exponential
                                            :retry/max-attempts 3
                                            :retry/initial-delay-ms 1000}
                   :connector/maintainer "test-team"})]
      (is (:success? result))
      (is (some? (get-in result [:connector :connector/id])))))

  (testing "Reject invalid connector type"
    (let [result (conn/create-connector
                  {:connector/name "Bad"
                   :connector/type :invalid
                   :connector/version "1.0.0"
                   :connector/capabilities #{:cap/batch}
                   :connector/auth-methods #{:api-key}
                   :connector/retry-policy {:retry/strategy :fixed :retry/max-attempts 1}
                   :connector/maintainer "x"})]
      (is (not (:success? result))))))

(deftest create-connector-auto-id-test
  (testing "Auto-generates :connector/id when not provided"
    (let [result (conn/create-connector
                  {:connector/name "Auto ID"
                   :connector/type :sink
                   :connector/version "2.0.0"
                   :connector/capabilities #{:cap/batch}
                   :connector/auth-methods #{:basic}
                   :connector/retry-policy {:retry/strategy :fixed
                                            :retry/max-attempts 1}
                   :connector/maintainer "team-a"})]
      (is (:success? result))
      (is (uuid? (get-in result [:connector :connector/id]))))))

(deftest create-connector-preserves-provided-id-test
  (testing "Preserves :connector/id when provided"
    (let [id (java.util.UUID/randomUUID)
          result (conn/create-connector
                  {:connector/id id
                   :connector/name "With ID"
                   :connector/type :bidirectional
                   :connector/version "1.0.0"
                   :connector/capabilities #{:cap/upsert}
                   :connector/auth-methods #{:oauth2}
                   :connector/retry-policy {:retry/strategy :exponential
                                            :retry/max-attempts 5
                                            :retry/initial-delay-ms 500}
                   :connector/maintainer "team-b"})]
      (is (:success? result))
      (is (= id (get-in result [:connector :connector/id]))))))

(deftest create-connector-missing-name-test
  (testing "Reject missing :connector/name"
    (let [result (conn/create-connector
                  {:connector/type :source
                   :connector/version "1.0.0"
                   :connector/capabilities #{:cap/batch}
                   :connector/auth-methods #{:api-key}
                   :connector/retry-policy {:retry/strategy :fixed :retry/max-attempts 1}
                   :connector/maintainer "team"})]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/name-must-be-string) %) (:errors result))))))

(deftest create-connector-missing-type-test
  (testing "Reject missing :connector/type"
    (let [result (conn/create-connector
                  {:connector/name "No Type"
                   :connector/version "1.0.0"
                   :connector/capabilities #{:cap/batch}
                   :connector/auth-methods #{:api-key}
                   :connector/retry-policy {:retry/strategy :fixed :retry/max-attempts 1}
                   :connector/maintainer "team"})]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/type-invalid {:allowed conn/connector-types}) %)
                (:errors result))))))

(deftest create-connector-missing-version-test
  (testing "Reject missing :connector/version"
    (let [result (conn/create-connector
                  {:connector/name "No Version"
                   :connector/type :source
                   :connector/capabilities #{:cap/batch}
                   :connector/auth-methods #{:api-key}
                   :connector/retry-policy {:retry/strategy :fixed :retry/max-attempts 1}
                   :connector/maintainer "team"})]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/version-must-be-string) %) (:errors result))))))

(deftest create-connector-missing-maintainer-test
  (testing "Reject missing :connector/maintainer"
    (let [result (conn/create-connector
                  {:connector/name "No Maintainer"
                   :connector/type :source
                   :connector/version "1.0.0"
                   :connector/capabilities #{:cap/batch}
                   :connector/auth-methods #{:api-key}
                   :connector/retry-policy {:retry/strategy :fixed :retry/max-attempts 1}})]
      (is (not (:success? result)))
      (is (some #(= (msg/t :connector/maintainer-must-be-string) %) (:errors result))))))

(deftest create-connector-empty-map-test
  (testing "Empty map returns multiple errors"
    (let [result (conn/create-connector {})]
      (is (not (:success? result)))
      (is (> (count (:errors result)) 1)))))

;; ---------------------------------------------------------------------------
;; connector-state
;; ---------------------------------------------------------------------------

(deftest connector-state-test
  (testing "State tracking"
    (let [conn-id (java.util.UUID/randomUUID)
          state (conn/create-connector-state conn-id)]
      (is (= conn-id (:connector-state/connector-id state)))
      (is (= :pending (:connector-state/status state)))
      (is (= 0 (:connector-state/records-processed state)))

      (let [updated (conn/record-batch state 100 2)]
        (is (= 100 (:connector-state/records-processed updated)))
        (is (= 2 (:connector-state/records-failed updated)))

        (let [again (conn/record-batch updated 50 0)]
          (is (= 150 (:connector-state/records-processed again)))
          (is (= 2 (:connector-state/records-failed again))))))))

(deftest connector-state-initial-fields-test
  (testing "Initial state has all required fields"
    (let [conn-id (java.util.UUID/randomUUID)
          state (conn/create-connector-state conn-id)]
      (is (uuid? (:connector-state/id state)))
      (is (= conn-id (:connector-state/connector-id state)))
      (is (= {} (:connector-state/cursor state)))
      (is (inst? (:connector-state/last-run state)))
      (is (= 0 (:connector-state/records-processed state)))
      (is (= 0 (:connector-state/records-failed state)))
      (is (= :pending (:connector-state/status state)))
      (is (= [] (:connector-state/checkpoints state))))))

(deftest connector-state-lifecycle-test
  (testing "Full lifecycle: create -> record-batch multiple times -> accumulate"
    (let [conn-id (java.util.UUID/randomUUID)
          state (conn/create-connector-state conn-id)
          after-batch-1 (conn/record-batch state 500 10)
          after-batch-2 (conn/record-batch after-batch-1 300 5)
          after-batch-3 (conn/record-batch after-batch-2 200 0)
          after-batch-4 (conn/record-batch after-batch-3 1000 50)]
      ;; Verify accumulation across four batches
      (is (= 2000 (:connector-state/records-processed after-batch-4)))
      (is (= 65 (:connector-state/records-failed after-batch-4)))
      ;; Connector-id unchanged
      (is (= conn-id (:connector-state/connector-id after-batch-4)))
      ;; last-run updated
      (is (inst? (:connector-state/last-run after-batch-4))))))

(deftest connector-state-record-batch-zero-test
  (testing "Recording zero-count batch is idempotent on counts"
    (let [state (conn/create-connector-state (java.util.UUID/randomUUID))
          after (conn/record-batch state 0 0)]
      (is (= 0 (:connector-state/records-processed after)))
      (is (= 0 (:connector-state/records-failed after))))))

;; ---------------------------------------------------------------------------
;; update-state
;; ---------------------------------------------------------------------------

(deftest update-state-status-change-test
  (testing "Update status from :pending to :running"
    (let [state (conn/create-connector-state (java.util.UUID/randomUUID))
          updated (conn/update-state state {:connector-state/status :running})]
      (is (= :running (:connector-state/status updated)))
      ;; Other fields preserved
      (is (= 0 (:connector-state/records-processed updated))))))

(deftest update-state-error-recording-test
  (testing "Record error information in state"
    (let [state (conn/create-connector-state (java.util.UUID/randomUUID))
          updated (conn/update-state state {:connector-state/status :error
                                            :connector-state/error-message "Connection timeout"})]
      (is (= :error (:connector-state/status updated)))
      (is (= "Connection timeout" (:connector-state/error-message updated))))))

(deftest update-state-cursor-test
  (testing "Update cursor in state"
    (let [state (conn/create-connector-state (java.util.UUID/randomUUID))
          cursor {:cursor/type :offset :cursor/value 42}
          updated (conn/update-state state {:connector-state/cursor cursor})]
      (is (= cursor (:connector-state/cursor updated))))))

(deftest update-state-multiple-fields-test
  (testing "Update multiple fields simultaneously"
    (let [state (conn/create-connector-state (java.util.UUID/randomUUID))
          updated (conn/update-state state {:connector-state/status :completed
                                            :connector-state/records-processed 999})]
      (is (= :completed (:connector-state/status updated)))
      (is (= 999 (:connector-state/records-processed updated))))))

(deftest update-state-preserves-id-test
  (testing "Update preserves connector-state/id and connector-id"
    (let [conn-id (java.util.UUID/randomUUID)
          state (conn/create-connector-state conn-id)
          state-id (:connector-state/id state)
          updated (conn/update-state state {:connector-state/status :running})]
      (is (= state-id (:connector-state/id updated)))
      (is (= conn-id (:connector-state/connector-id updated))))))

;; ---------------------------------------------------------------------------
;; cursor
;; ---------------------------------------------------------------------------

(deftest cursor-test
  (testing "N2 §2.2.1 cursor types"
    (is (= #{:timestamp-watermark :offset :sequence-id :version-id}
           conn/cursor-types)))

  (testing "Create and advance cursor"
    (let [cursor (conn/create-cursor :timestamp-watermark #inst "2026-01-01")]
      (is (= :timestamp-watermark (:cursor/type cursor)))
      (is (= #inst "2026-01-01" (:cursor/value cursor)))

      (let [advanced (conn/advance-cursor cursor #inst "2026-03-13")]
        (is (= #inst "2026-03-13" (:cursor/value advanced))))))

  (testing "Validate cursor"
    (is (:success? (conn/validate-cursor {:cursor/type :offset})))
    (is (not (:success? (conn/validate-cursor {:cursor/type :invalid}))))
    (is (not (:success? (conn/validate-cursor {}))))))

(deftest cursor-all-types-test
  (testing "All cursor types are valid"
    (doseq [ct conn/cursor-types]
      (is (:success? (conn/validate-cursor {:cursor/type ct}))
          (str "Cursor type " ct " should be valid")))))

(deftest cursor-invalid-type-test
  (testing "Invalid cursor types rejected"
    (doseq [bad [:invalid :unknown :random :timestamp nil]]
      (let [result (conn/validate-cursor {:cursor/type bad})]
        (is (not (:success? result))
            (str "Cursor type " bad " should be invalid"))))))

(deftest cursor-missing-type-test
  (testing "Missing :cursor/type"
    (let [result (conn/validate-cursor {})]
      (is (not (:success? result)))
      (is (some #(= (msg/t :cursor/type-required) %) (:errors result))))))

(deftest cursor-nil-type-test
  (testing "Explicit nil :cursor/type"
    (let [result (conn/validate-cursor {:cursor/type nil})]
      (is (not (:success? result)))
      (is (some #(= (msg/t :cursor/type-required) %) (:errors result))))))

(deftest cursor-invalid-type-error-message-test
  (testing "Invalid cursor type produces correct error message"
    (let [result (conn/validate-cursor {:cursor/type :bogus})]
      (is (not (:success? result)))
      (is (some #(= (msg/t :cursor/type-invalid {:allowed conn/cursor-types}) %)
                (:errors result))))))

(deftest cursor-create-offset-test
  (testing "Create offset cursor"
    (let [cursor (conn/create-cursor :offset 0)]
      (is (= :offset (:cursor/type cursor)))
      (is (= 0 (:cursor/value cursor)))
      (is (inst? (:cursor/last-retrieved-at cursor))))))

(deftest cursor-create-sequence-id-test
  (testing "Create sequence-id cursor"
    (let [cursor (conn/create-cursor :sequence-id "seq-001")]
      (is (= :sequence-id (:cursor/type cursor)))
      (is (= "seq-001" (:cursor/value cursor))))))

(deftest cursor-advance-preserves-type-test
  (testing "Advancing cursor preserves type"
    (let [cursor (conn/create-cursor :offset 0)
          advanced (conn/advance-cursor cursor 100)]
      (is (= :offset (:cursor/type advanced)))
      (is (= 100 (:cursor/value advanced)))
      (is (inst? (:cursor/last-retrieved-at advanced))))))

(deftest cursor-advance-updates-timestamp-test
  (testing "Advancing cursor updates last-retrieved-at"
    (let [cursor (conn/create-cursor :offset 0)
          t1 (:cursor/last-retrieved-at cursor)
          _ (Thread/sleep 5)
          advanced (conn/advance-cursor cursor 1)
          t2 (:cursor/last-retrieved-at advanced)]
      (is (.isAfter t2 t1)))))

(deftest cursor-empty-map-test
  (testing "Empty map fails validation"
    (let [result (conn/validate-cursor {})]
      (is (not (:success? result)))
      (is (= 1 (count (:errors result)))))))
