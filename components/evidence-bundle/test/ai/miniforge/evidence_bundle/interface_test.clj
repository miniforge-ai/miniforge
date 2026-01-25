(ns ai.miniforge.evidence-bundle.interface-test
  "Tests for evidence-bundle component public interface."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.artifact.interface :as artifact]))

;------------------------------------------------------------------------------ Test Helpers

(defn create-test-artifact-store
  "Create an in-memory artifact store for testing."
  []
  (artifact/create-transit-store {:dir nil}))

(defn create-test-workflow-state
  "Create a test workflow state."
  [workflow-id status & {:keys [tool-invocations] :or {tool-invocations []}}]
  {:workflow/id workflow-id
   :workflow/status status
   :workflow/spec {:intent/type :import
                   :title "Test Workflow"
                   :description "Import RDS instance"
                   :business-reason "Enable IaC management"}
   :workflow/phases {:plan {:agent :planner
                           :started-at (java.time.Instant/now)
                           :completed-at (java.time.Instant/now)
                           :duration-ms 1000
                           :output {:plan "Test plan"}
                           :artifacts []}
                     :implement {:agent :implementer
                                :started-at (java.time.Instant/now)
                                :completed-at (java.time.Instant/now)
                                :duration-ms 2000
                                :output {:implementation "Test impl"}
                                :artifacts []}}
   :workflow/gate-results []
   :workflow/tool-invocations tool-invocations
   :workflow/pr-info {:number 123
                      :url "https://github.com/test/repo/pull/123"
                      :status :merged
                      :merged-at (java.time.Instant/now)}})

;------------------------------------------------------------------------------ Layer 1: Evidence Manager Creation

(deftest create-evidence-manager-test
  (testing "Creates evidence manager with artifact store"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})]
      (is (not (nil? manager)))
      (is (satisfies? evidence/EvidenceBundle manager))
      (is (satisfies? evidence/ProvenanceTracer manager))
      (is (satisfies? evidence/SemanticValidator manager))))

  (testing "Requires artifact store"
    (is (thrown? Exception
                 (evidence/create-evidence-manager {})))))

;------------------------------------------------------------------------------ Layer 2: Bundle Creation

(deftest create-bundle-test
  (testing "Creates evidence bundle from workflow state"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          workflow-id (random-uuid)
          state (create-test-workflow-state workflow-id :completed)
          bundle (evidence/create-bundle manager workflow-id {:workflow-state state})]

      (is (not (nil? bundle)))
      (is (uuid? (:evidence-bundle/id bundle)))
      (is (= workflow-id (:evidence-bundle/workflow-id bundle)))
      (is (inst? (:evidence-bundle/created-at bundle)))
      (is (= "1.0.0" (:evidence-bundle/version bundle)))

      ;; Check intent
      (is (= :import (get-in bundle [:evidence/intent :intent/type])))
      (is (string? (get-in bundle [:evidence/intent :intent/description])))

      ;; Check phase evidence
      (is (map? (:evidence/plan bundle)))
      (is (= :plan (get-in bundle [:evidence/plan :phase/name])))
      (is (map? (:evidence/implement bundle)))
      (is (= :implement (get-in bundle [:evidence/implement :phase/name])))

      ;; Check outcome
      (is (true? (get-in bundle [:evidence/outcome :outcome/success])))
      (is (= 123 (get-in bundle [:evidence/outcome :outcome/pr-number])))))

  (testing "Includes tool invocations when present"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          workflow-id (random-uuid)
          invocations [{:tool/id :tools/demo
                        :tool/invoked-at (java.time.Instant/now)
                        :tool/duration-ms 12
                        :tool/args {:x 1}
                        :tool/result {:ok true}}]
          state (create-test-workflow-state workflow-id :completed
                                            :tool-invocations invocations)
          bundle (evidence/create-bundle manager workflow-id {:workflow-state state})]
      (is (= invocations (:evidence/tool-invocations bundle)))))

  (testing "Creates bundle for failed workflow"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          workflow-id (random-uuid)
          state (assoc (create-test-workflow-state workflow-id :failed)
                       :workflow/error {:message "Test error"
                                        :phase :verify})
          bundle (evidence/create-bundle manager workflow-id {:workflow-state state})]

      (is (not (nil? bundle)))
      (is (false? (get-in bundle [:evidence/outcome :outcome/success])))
      (is (= "Test error" (get-in bundle [:evidence/outcome :outcome/error-message])))
      (is (= :verify (get-in bundle [:evidence/outcome :outcome/error-phase]))))))

;------------------------------------------------------------------------------ Layer 3: Bundle Retrieval

(deftest get-bundle-test
  (testing "Retrieves bundle by ID"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          workflow-id (random-uuid)
          state (create-test-workflow-state workflow-id :completed)
          bundle (evidence/create-bundle manager workflow-id {:workflow-state state})
          bundle-id (:evidence-bundle/id bundle)]

      (let [retrieved (evidence/get-bundle manager bundle-id)]
        (is (= bundle-id (:evidence-bundle/id retrieved)))
        (is (= workflow-id (:evidence-bundle/workflow-id retrieved))))))

  (testing "Returns nil for non-existent bundle"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})]
      (is (nil? (evidence/get-bundle manager (random-uuid)))))))

(deftest get-bundle-by-workflow-test
  (testing "Retrieves bundle by workflow ID"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          workflow-id (random-uuid)
          state (create-test-workflow-state workflow-id :completed)
          bundle (evidence/create-bundle manager workflow-id {:workflow-state state})]

      (let [retrieved (evidence/get-bundle-by-workflow manager workflow-id)]
        (is (= workflow-id (:evidence-bundle/workflow-id retrieved)))
        (is (= (:evidence-bundle/id bundle) (:evidence-bundle/id retrieved)))))))

;------------------------------------------------------------------------------ Layer 4: Bundle Querying

(deftest query-bundles-test
  (testing "Queries bundles by intent type"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          workflow-id-1 (random-uuid)
          workflow-id-2 (random-uuid)
          state-1 (create-test-workflow-state workflow-id-1 :completed)
          state-2 (assoc-in (create-test-workflow-state workflow-id-2 :completed)
                            [:workflow/spec :intent/type] :create)]

      (evidence/create-bundle manager workflow-id-1 {:workflow-state state-1})
      (evidence/create-bundle manager workflow-id-2 {:workflow-state state-2})

      (let [import-bundles (evidence/query-bundles manager {:intent-type :import})
            create-bundles (evidence/query-bundles manager {:intent-type :create})]
        (is (= 1 (count import-bundles)))
        (is (= 1 (count create-bundles)))
        (is (= :import (get-in (first import-bundles) [:evidence/intent :intent/type])))
        (is (= :create (get-in (first create-bundles) [:evidence/intent :intent/type]))))))

  (testing "Queries all bundles with empty criteria"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          workflow-id (random-uuid)
          state (create-test-workflow-state workflow-id :completed)]

      (evidence/create-bundle manager workflow-id {:workflow-state state})

      (let [all-bundles (evidence/query-bundles manager {})]
        (is (= 1 (count all-bundles)))))))

;------------------------------------------------------------------------------ Layer 5: Bundle Validation

(deftest validate-bundle-test
  (testing "Validates valid bundle"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          workflow-id (random-uuid)
          state (create-test-workflow-state workflow-id :completed)
          bundle (evidence/create-bundle manager workflow-id {:workflow-state state})
          validation (evidence/validate-bundle manager bundle)]

      (is (:valid? validation))
      (is (empty? (:errors validation)))))

  (testing "Detects invalid bundle structure"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          invalid-bundle {:evidence-bundle/id (random-uuid)}
          validation (evidence/validate-bundle manager invalid-bundle)]

      (is (not (:valid? validation)))
      (is (seq (:errors validation))))))

;------------------------------------------------------------------------------ Layer 6: Bundle Export

(deftest export-bundle-test
  (testing "Exports bundle to file"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          workflow-id (random-uuid)
          state (create-test-workflow-state workflow-id :completed)
          bundle (evidence/create-bundle manager workflow-id {:workflow-state state})
          bundle-id (:evidence-bundle/id bundle)
          output-path "/tmp/test-evidence.edn"]

      (is (true? (evidence/export-bundle manager bundle-id output-path)))
      (is (.exists (java.io.File. output-path)))

      ;; Clean up
      (.delete (java.io.File. output-path)))))

;------------------------------------------------------------------------------ Layer 7: Semantic Validation

(deftest validate-intent-test
  (testing "Validates import intent with no changes"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          intent {:intent/type :import}
          artifacts []
          result (evidence/validate-intent manager intent artifacts)]

      (is (:passed? result))
      (is (= :import (:semantic-validation/declared-intent result)))
      (is (= 0 (:semantic-validation/resource-creates result)))))

  (testing "Detects intent violation for import with creates"
    (let [store (create-test-artifact-store)
          manager (evidence/create-evidence-manager {:artifact-store store})
          intent {:intent/type :import}
          artifacts [{:artifact/type :terraform-plan
                      :artifact/content " will be created\n will be created"}]
          result (evidence/validate-intent manager intent artifacts)]

      (is (not (:passed? result)))
      (is (seq (:violations result)))
      (is (= 2 (:semantic-validation/resource-creates result))))))

;------------------------------------------------------------------------------ Layer 8: Evidence Collection Helpers

(deftest extract-intent-test
  (testing "Extracts intent from workflow spec"
    (let [spec {:intent/type :create
                :description "Create new resources"
                :business-reason "Launch new feature"}
          intent (evidence/extract-intent spec)]

      (is (= :create (:intent/type intent)))
      (is (= "Create new resources" (:intent/description intent)))
      (is (= "Launch new feature" (:intent/business-reason intent)))
      (is (inst? (:intent/declared-at intent))))))

(deftest auto-collect-evidence-test
  (testing "Collects evidence for completed workflow"
    (let [store (create-test-artifact-store)
          workflow-id (random-uuid)
          state (create-test-workflow-state workflow-id :completed)
          bundle (evidence/auto-collect-evidence workflow-id state store)]

      (is (not (nil? bundle)))
      (is (= workflow-id (:evidence-bundle/workflow-id bundle)))))

  (testing "Returns nil for running workflow"
    (let [store (create-test-artifact-store)
          workflow-id (random-uuid)
          state (assoc (create-test-workflow-state workflow-id :completed)
                       :workflow/status :running)
          bundle (evidence/auto-collect-evidence workflow-id state store)]

      (is (nil? bundle)))))

;------------------------------------------------------------------------------ Layer 9: Schema Exports

(deftest schema-exports-test
  (testing "Exports intent types"
    (is (set? evidence/intent-types))
    (is (contains? evidence/intent-types :import))
    (is (contains? evidence/intent-types :create)))

  (testing "Exports semantic validation rules"
    (is (map? evidence/semantic-validation-rules))
    (is (contains? evidence/semantic-validation-rules :import)))

  (testing "Creates evidence template"
    (let [template (evidence/create-evidence-template)]
      (is (uuid? (:evidence-bundle/id template)))
      (is (inst? (:evidence-bundle/created-at template)))
      (is (= "1.0.0" (:evidence-bundle/version template))))))
