(ns ai.miniforge.knowledge.interface-test
  "Tests for the knowledge component public interface."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.knowledge.interface :as k]))

;------------------------------------------------------------------------------ Layer 0
;; Zettel creation tests

(deftest create-zettel-test
  (testing "Creates zettel with required fields"
    (let [z (k/create-zettel "test-uid" "Test Title" "Test content" :rule)]
      (is (uuid? (:zettel/id z)))
      (is (= "test-uid" (:zettel/uid z)))
      (is (= "Test Title" (:zettel/title z)))
      (is (= "Test content" (:zettel/content z)))
      (is (= :rule (:zettel/type z)))
      (is (inst? (:zettel/created z)))
      (is (= "user" (:zettel/author z)))))

  (testing "Creates zettel with optional fields"
    (let [z (k/create-zettel "210-clojure" "Clojure Rules"
                             "# Clojure\n\nFollow these rules..." :rule
                             :dewey "210"
                             :tags [:clojure :coding]
                             :author "system")]
      (is (= "210" (:zettel/dewey z)))
      (is (= [:clojure :coding] (:zettel/tags z)))
      (is (= "system" (:zettel/author z))))))

(deftest validate-zettel-test
  (testing "Valid zettel passes validation"
    (let [z (k/create-zettel "test" "Title" "Content" :rule)
          result (k/validate-zettel z)]
      (is (:valid? result))
      (is (nil? (:errors result)))))

  (testing "Invalid zettel fails validation"
    (let [result (k/validate-zettel {:zettel/id "not-a-uuid"})]
      (is (not (:valid? result)))
      (is (some? (:errors result))))))

;------------------------------------------------------------------------------ Layer 1
;; Link management tests

(deftest create-link-test
  (testing "Creates link with required fields"
    (let [target-id (random-uuid)
          link (k/create-link target-id :extends "Extends the base concept")]
      (is (= target-id (:link/target-id link)))
      (is (= :extends (:link/type link)))
      (is (= "Extends the base concept" (:link/rationale link)))))

  (testing "Creates link with optional fields"
    (let [target-id (random-uuid)
          link (k/create-link target-id :supports "Provides evidence"
                              :strength 0.8
                              :bidirectional? true)]
      (is (= 0.8 (:link/strength link)))
      (is (true? (:link/bidirectional? link))))))

(deftest add-link-test
  (testing "Adds link to zettel"
    (let [z (k/create-zettel "test" "Title" "Content" :rule)
          link (k/create-link (random-uuid) :extends "Extension rationale")
          z2 (k/add-link z link)]
      (is (= 1 (count (:zettel/links z2))))
      (is (inst? (:zettel/modified z2))))))

(deftest remove-link-test
  (testing "Removes link by target ID"
    (let [target-id (random-uuid)
          z (-> (k/create-zettel "test" "Title" "Content" :rule)
                (k/add-link (k/create-link target-id :extends "Rationale")))
          z2 (k/remove-link z target-id)]
      (is (empty? (:zettel/links z2))))))

;------------------------------------------------------------------------------ Layer 2
;; Store tests

(deftest store-crud-test
  (testing "CRUD operations on knowledge store"
    (let [store (k/create-store)
          z (k/create-zettel "test-uid" "Test Title" "Content" :rule)]

      ;; Create
      (k/put-zettel store z)

      ;; Read by UID
      (let [retrieved (k/get-zettel store "test-uid")]
        (is (= (:zettel/id z) (:zettel/id retrieved)))
        (is (= "Test Title" (:zettel/title retrieved))))

      ;; Read by ID
      (let [retrieved (k/get-zettel store (:zettel/id z))]
        (is (= "test-uid" (:zettel/uid retrieved))))

      ;; List
      (let [summaries (k/list-zettels store)]
        (is (= 1 (count summaries)))
        (is (= "test-uid" (:zettel/uid (first summaries)))))

      ;; Delete
      (k/delete-zettel store (:zettel/id z))
      (is (nil? (k/get-zettel store "test-uid"))))))

;------------------------------------------------------------------------------ Layer 3
;; Query tests

(deftest query-by-tags-test
  (testing "Query zettels by tags"
    (let [store (k/create-store)]
      (k/put-zettel store (k/create-zettel "z1" "Z1" "C1" :rule :tags [:clojure]))
      (k/put-zettel store (k/create-zettel "z2" "Z2" "C2" :rule :tags [:python]))
      (k/put-zettel store (k/create-zettel "z3" "Z3" "C3" :rule :tags [:clojure :testing]))

      (let [results (k/query-knowledge store {:tags [:clojure]})]
        (is (= 2 (count results)))
        (is (every? #(some #{:clojure} (:zettel/tags %)) results))))))

(deftest query-by-type-test
  (testing "Query zettels by type inclusion"
    (let [store (k/create-store)]
      (k/put-zettel store (k/create-zettel "r1" "Rule" "C" :rule))
      (k/put-zettel store (k/create-zettel "c1" "Concept" "C" :concept))
      (k/put-zettel store (k/create-zettel "l1" "Learning" "C" :learning))

      (let [results (k/query-knowledge store {:include-types [:rule :concept]})]
        (is (= 2 (count results)))
        (is (every? #(#{:rule :concept} (:zettel/type %)) results)))))

  (testing "Query zettels by type exclusion"
    (let [store (k/create-store)]
      (k/put-zettel store (k/create-zettel "r1" "Rule" "C" :rule))
      (k/put-zettel store (k/create-zettel "l1" "Learning" "C" :learning))

      (let [results (k/query-knowledge store {:exclude-types [:learning]})]
        (is (= 1 (count results)))
        (is (= :rule (:zettel/type (first results))))))))

(deftest query-by-dewey-test
  (testing "Query zettels by Dewey prefix"
    (let [store (k/create-store)]
      (k/put-zettel store (k/create-zettel "z1" "Z1" "C" :rule :dewey "210"))
      (k/put-zettel store (k/create-zettel "z2" "Z2" "C" :rule :dewey "220"))
      (k/put-zettel store (k/create-zettel "z3" "Z3" "C" :rule :dewey "310"))

      (let [results (k/query-knowledge store {:dewey-prefixes ["21" "22"]})]
        (is (= 2 (count results)))
        (is (every? #(or (= "210" (:zettel/dewey %))
                         (= "220" (:zettel/dewey %)))
                    results))))))

(deftest text-search-test
  (testing "Full-text search in title and content"
    (let [store (k/create-store)]
      (k/put-zettel store (k/create-zettel "z1" "Clojure Namespaces" "How to organize" :rule))
      (k/put-zettel store (k/create-zettel "z2" "Python Modules" "Import statements" :rule))
      (k/put-zettel store (k/create-zettel "z3" "Testing" "Clojure test patterns" :rule))

      (let [results (k/search store "clojure")]
        (is (= 2 (count results)))))))

;------------------------------------------------------------------------------ Layer 4
;; Serialization tests

(deftest markdown-roundtrip-test
  (testing "Zettel serializes to and from Markdown"
    (let [z (k/create-zettel "210-clojure" "Clojure Conventions"
                             "Follow Polylith structure..." :rule
                             :dewey "210"
                             :tags [:clojure :namespace])
          markdown (k/zettel->markdown z)
          z2 (k/markdown->zettel markdown)]
      (is (string? markdown))
      (is (some? z2))
      (is (= (:zettel/uid z) (:zettel/uid z2)))
      (is (= (:zettel/type z) (:zettel/type z2)))
      (is (= (:zettel/dewey z) (:zettel/dewey z2)))
      (is (= (:zettel/tags z) (:zettel/tags z2))))))

;------------------------------------------------------------------------------ Layer 5
;; Agent injection tests

(deftest agent-manifest-test
  (testing "Returns manifest for known roles"
    (let [manifest (k/get-agent-manifest :planner)]
      (is (= :planner (:agent-role manifest)))
      (is (vector? (:dewey-prefixes manifest)))
      (is (vector? (:types manifest)))))

  (testing "Returns default manifest for unknown roles"
    (let [manifest (k/get-agent-manifest :unknown-role)]
      (is (= :unknown-role (:agent-role manifest)))
      (is (= [:rule] (:types manifest))))))

(deftest inject-knowledge-test
  (testing "Injects knowledge for agent role"
    (let [store (k/create-store)]
      ;; Add zettels matching planner manifest (000, 700 prefixes)
      (k/put-zettel store (k/create-zettel "arch" "Architecture" "C" :rule
                                           :dewey "000" :tags [:architecture]))
      (k/put-zettel store (k/create-zettel "workflow" "Workflow" "C" :rule
                                           :dewey "700" :tags [:workflow]))
      ;; Add zettel NOT matching planner manifest (wrong dewey prefix)
      (k/put-zettel store (k/create-zettel "code" "Coding" "C" :rule
                                           :dewey "210" :tags [:coding]))

      (let [results (k/inject-knowledge store :planner)]
        ;; Should get arch and workflow, not code
        (is (= 2 (count results)))
        (is (every? #(or (= "000" (:zettel/dewey %))
                         (= "700" (:zettel/dewey %)))
                    results))))))

;------------------------------------------------------------------------------ Layer 6
;; Learning capture tests

(deftest capture-learning-test
  (testing "Captures learning from agent execution"
    (let [store (k/create-store)
          learning (k/capture-learning store
                                       {:type :inner-loop
                                        :title "Protocol naming insight"
                                        :content "Avoid JVM method names in protocols"
                                        :agent :implementer
                                        :tags [:clojure :protocol]})]
      (is (= :learning (:zettel/type learning)))
      (is (string? (:zettel/uid learning)))
      (is (.startsWith (:zettel/uid learning) "L-"))
      (is (= :inner-loop (get-in learning [:zettel/source :source/type]))))))

(deftest list-learnings-test
  (testing "Lists learnings with filters"
    (let [store (k/create-store)]
      (k/capture-learning store {:type :inner-loop :title "L1" :content "C1"
                                 :confidence 0.6})
      (k/capture-learning store {:type :inner-loop :title "L2" :content "C2"
                                 :confidence 0.9})

      ;; All learnings
      (is (= 2 (count (k/list-learnings store))))

      ;; High confidence only
      (let [high-conf (k/list-learnings store {:min-confidence 0.8})]
        (is (= 1 (count high-conf)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run tests
  (clojure.test/run-tests 'ai.miniforge.knowledge.interface-test)

  :leave-this-here)
