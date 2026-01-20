(ns ai.miniforge.artifact.interface-test
  (:require [clojure.test :as test :refer [deftest testing is use-fixtures]]
            [ai.miniforge.artifact.interface :as artifact]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def test-stores (atom #{}))

(defn cleanup-stores [f]
  (f)
  (doseq [store @test-stores]
    (artifact/close-store store))
  (reset! test-stores #{}))

(use-fixtures :each cleanup-stores)

(defn create-test-store []
  (let [store (artifact/create-store)]
    (swap! test-stores conj store)
    store))

;------------------------------------------------------------------------------ Layer 1
;; Store creation tests

(deftest create-store-test
  (testing "creates in-memory store"
    (let [store (create-test-store)]
      (is (some? store))
      (is (satisfies? artifact/ArtifactStore store))))

  (testing "creates store with logger"
    (let [store (artifact/create-store {:logger nil})]
      (swap! test-stores conj store)
      (is (some? store)))))

;; Artifact building tests

(deftest build-artifact-test
  (testing "builds artifact with required fields"
    (let [art (artifact/build-artifact
               {:id (random-uuid)
                :type :code
                :version "1.0.0"
                :content {:code "(defn foo [])"}})]
      (is (uuid? (:artifact/id art)))
      (is (= :code (:artifact/type art)))
      (is (= "1.0.0" (:artifact/version art)))
      (is (map? (:artifact/content art)))))

  (testing "builds artifact with optional fields"
    (let [agent-id (random-uuid)
          task-id (random-uuid)
          art (artifact/build-artifact
               {:id (random-uuid)
                :type :test
                :version "2.0.0"
                :content "test content"
                :origin {:agent-id agent-id :task-id task-id}
                :parents [(random-uuid)]
                :metadata {:language :clojure}})]
      (is (= agent-id (get-in art [:artifact/origin :agent-id])))
      (is (= task-id (get-in art [:artifact/origin :task-id])))
      (is (vector? (:artifact/parents art)))
      (is (= 1 (count (:artifact/parents art))))
      (is (= :clojure (get-in art [:artifact/metadata :language]))))))

;; Save and load tests

(deftest save-load-test
  (testing "saves and loads artifact"
    (let [store (create-test-store)
          art-id (random-uuid)
          art (artifact/build-artifact
               {:id art-id
                :type :code
                :version "1.0.0"
                :content {:file "foo.clj" :code "(defn hello [] \"world\")"}})]
      (is (= art-id (artifact/save! store art)))
      (let [loaded (artifact/load store art-id)]
        (is (some? loaded))
        (is (= art-id (:artifact/id loaded)))
        (is (= :code (:artifact/type loaded)))
        (is (= "1.0.0" (:artifact/version loaded))))))

  (testing "returns nil for non-existent artifact"
    (let [store (create-test-store)]
      (is (nil? (artifact/load store (random-uuid)))))))

;; Query tests

(deftest query-test
  (testing "queries artifacts by type"
    (let [store (create-test-store)
          code-id-1 (random-uuid)
          code-id-2 (random-uuid)
          test-id (random-uuid)]
      (artifact/save! store (artifact/build-artifact
                             {:id code-id-1 :type :code :version "1.0.0" :content "code1"}))
      (artifact/save! store (artifact/build-artifact
                             {:id code-id-2 :type :code :version "1.0.0" :content "code2"}))
      (artifact/save! store (artifact/build-artifact
                             {:id test-id :type :test :version "1.0.0" :content "test1"}))
      (let [code-artifacts (artifact/query store {:artifact/type :code})]
        (is (= 2 (count code-artifacts)))
        (is (every? #(= :code (:artifact/type %)) code-artifacts)))))

  (testing "queries artifacts by multiple criteria"
    (let [store (create-test-store)
          id1 (random-uuid)
          id2 (random-uuid)]
      (artifact/save! store (artifact/build-artifact
                             {:id id1 :type :code :version "1.0.0" :content "v1"}))
      (artifact/save! store (artifact/build-artifact
                             {:id id2 :type :code :version "2.0.0" :content "v2"}))
      (let [results (artifact/query store {:artifact/type :code :artifact/version "1.0.0"})]
        (is (= 1 (count results)))
        (is (= id1 (:artifact/id (first results))))))))

(deftest query-by-type-test
  (testing "convenience function for querying by type"
    (let [store (create-test-store)]
      (artifact/save! store (artifact/build-artifact
                             {:id (random-uuid) :type :spec :version "1.0.0" :content "spec"}))
      (let [specs (artifact/query-by-type store :spec)]
        (is (= 1 (count specs)))
        (is (= :spec (:artifact/type (first specs))))))))

;; Provenance and linking tests

(deftest link-test
  (testing "establishes provenance link between artifacts"
    (let [store (create-test-store)
          parent-id (random-uuid)
          child-id (random-uuid)]
      (artifact/save! store (artifact/build-artifact
                             {:id parent-id :type :spec :version "1.0.0" :content "parent"}))
      (artifact/save! store (artifact/build-artifact
                             {:id child-id :type :code :version "1.0.0" :content "child"}))
      (is (true? (artifact/link! store parent-id child-id)))
      (let [parent (artifact/load store parent-id)
            child (artifact/load store child-id)]
        (is (some #(= child-id %) (:artifact/children parent)))
        (is (some #(= parent-id %) (:artifact/parents child)))))))

(deftest get-provenance-test
  (testing "retrieves provenance chain"
    (let [store (create-test-store)
          parent-id (random-uuid)
          child-id (random-uuid)]
      (artifact/save! store (artifact/build-artifact
                             {:id parent-id :type :spec :version "1.0.0" :content "parent"}))
      (artifact/save! store (artifact/build-artifact
                             {:id child-id :type :code :version "1.0.0" :content "child"}))
      (artifact/link! store parent-id child-id)
      (let [prov (artifact/get-provenance store child-id)]
        (is (some? prov))
        (is (some #(= parent-id %) (:ancestors prov)))))))

(deftest add-parent-child-test
  (testing "adds parent to artifact"
    (let [art (artifact/build-artifact
               {:id (random-uuid) :type :code :version "1.0.0" :content "code"})
          parent-id (random-uuid)
          updated (artifact/add-parent art parent-id)]
      (is (some #(= parent-id %) (:artifact/parents updated)))))

  (testing "adds child to artifact"
    (let [art (artifact/build-artifact
               {:id (random-uuid) :type :code :version "1.0.0" :content "code"})
          child-id (random-uuid)
          updated (artifact/add-child art child-id)]
      (is (some #(= child-id %) (:artifact/children updated))))))

;; Origin query tests

(deftest query-by-origin-test
  (testing "finds artifacts by task ID"
    (let [store (create-test-store)
          task-id (random-uuid)
          other-task-id (random-uuid)]
      (artifact/save! store (artifact/build-artifact
                             {:id (random-uuid)
                              :type :code
                              :version "1.0.0"
                              :content "code1"
                              :origin {:task-id task-id :agent-id (random-uuid)}}))
      (artifact/save! store (artifact/build-artifact
                             {:id (random-uuid)
                              :type :code
                              :version "1.0.0"
                              :content "code2"
                              :origin {:task-id other-task-id :agent-id (random-uuid)}}))
      (let [results (artifact/query-by-origin store {:task-id task-id})]
        (is (= 1 (count results)))
        (is (= task-id (get-in (first results) [:artifact/origin :task-id]))))))

  (testing "finds artifacts by agent ID"
    (let [store (create-test-store)
          agent-id (random-uuid)]
      (artifact/save! store (artifact/build-artifact
                             {:id (random-uuid)
                              :type :test
                              :version "1.0.0"
                              :content "test"
                              :origin {:agent-id agent-id :task-id (random-uuid)}}))
      (let [results (artifact/query-by-origin store {:agent-id agent-id})]
        (is (= 1 (count results)))
        (is (= agent-id (get-in (first results) [:artifact/origin :agent-id])))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.artifact.interface-test)

  :leave-this-here)
