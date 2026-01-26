(ns ai.miniforge.knowledge.trust-test
  "Tests for transitive trust rules (N1 §2.10.2).

   Validates all four transitive trust rules:
   1. Instruction authority is not transitive
   2. Trust level inheritance (lowest wins)
   3. Cross-trust references (no cycles, missing deps)
   4. Tainted isolation from instruction authority"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.knowledge.trust :as trust]))

;------------------------------------------------------------------------------ Layer 0
;; Trust level ordering tests

(deftest trust-level-order-test
  (testing "Trust levels have correct ordering"
    (is (= 0 (trust/trust-level-order :tainted)))
    (is (= 1 (trust/trust-level-order :untrusted)))
    (is (= 2 (trust/trust-level-order :trusted))))

  (testing "Invalid trust level returns nil"
    (is (nil? (trust/trust-level-order :invalid)))))

(deftest lowest-trust-level-test
  (testing "Returns lowest trust level from collection"
    (is (= :tainted (trust/lowest-trust-level [:tainted :untrusted :trusted])))
    (is (= :untrusted (trust/lowest-trust-level [:untrusted :trusted])))
    (is (= :trusted (trust/lowest-trust-level [:trusted :trusted]))))

  (testing "Single level returns that level"
    (is (= :trusted (trust/lowest-trust-level [:trusted])))
    (is (= :untrusted (trust/lowest-trust-level [:untrusted]))))

  (testing "Empty collection returns nil"
    (is (nil? (trust/lowest-trust-level []))))

  (testing "Filters invalid levels and returns valid ones"
    (is (= :untrusted (trust/lowest-trust-level [:invalid :untrusted :trusted]))))

  (testing "All invalid levels defaults to :untrusted"
    (is (= :untrusted (trust/lowest-trust-level [:invalid :bad])))))

;------------------------------------------------------------------------------ Layer 1
;; Pack reference creation and validation tests

(deftest make-pack-ref-test
  (testing "Creates pack reference with required fields"
    (let [pack-ref (trust/make-pack-ref "pack-a" :trusted :authority/instruction)]
      (is (= "pack-a" (:pack-id pack-ref)))
      (is (= :trusted (:trust-level pack-ref)))
      (is (= :authority/instruction (:authority pack-ref)))
      (is (= [] (:dependencies pack-ref)))))

  (testing "Creates pack reference with dependencies"
    (let [pack-ref (trust/make-pack-ref "pack-a" :trusted :authority/instruction
                                       :dependencies ["pack-b" "pack-c"])]
      (is (= ["pack-b" "pack-c"] (:dependencies pack-ref))))))

(deftest valid-pack-ref-test
  (testing "Valid pack reference passes validation"
    (let [pack-ref (trust/make-pack-ref "pack-a" :trusted :authority/instruction)]
      (is (trust/valid-pack-ref? pack-ref))))

  (testing "Invalid pack reference fails validation"
    (is (not (trust/valid-pack-ref? {})))
    (is (not (trust/valid-pack-ref? {:pack-id "a"})))
    (is (not (trust/valid-pack-ref? {:pack-id "a" :trust-level :invalid})))))

;------------------------------------------------------------------------------ Layer 2
;; Rule 1: Instruction authority is not transitive

(deftest instruction-authority-not-transitive-test
  (testing "Trusted pack can reference untrusted data pack"
    (let [trusted-pack (trust/make-pack-ref "pack-a" :trusted :authority/instruction)
          untrusted-pack (trust/make-pack-ref "pack-b" :untrusted :authority/data)
          result (trust/validate-instruction-authority-not-transitive
                  trusted-pack untrusted-pack)]
      (is (:valid? result))))

  (testing "Trusted pack can reference trusted instruction pack"
    (let [trusted-pack-a (trust/make-pack-ref "pack-a" :trusted :authority/instruction)
          trusted-pack-b (trust/make-pack-ref "pack-b" :trusted :authority/instruction)
          result (trust/validate-instruction-authority-not-transitive
                  trusted-pack-a trusted-pack-b)]
      (is (:valid? result))))

  (testing "INVALID: Trusted pack cannot grant instruction authority to untrusted pack"
    (let [trusted-pack (trust/make-pack-ref "pack-a" :trusted :authority/instruction)
          untrusted-instruction (trust/make-pack-ref "pack-b" :untrusted :authority/instruction)
          result (trust/validate-instruction-authority-not-transitive
                  trusted-pack untrusted-instruction)]
      (is (not (:valid? result)))
      (is (string? (:error result)))
      (is (re-find #"instruction authority cannot be granted transitively"
                   (clojure.string/lower-case (:error result))))))

  (testing "Data-only packs don't trigger rule"
    (let [data-pack-a (trust/make-pack-ref "pack-a" :trusted :authority/data)
          data-pack-b (trust/make-pack-ref "pack-b" :untrusted :authority/data)
          result (trust/validate-instruction-authority-not-transitive
                  data-pack-a data-pack-b)]
      (is (:valid? result)))))

;------------------------------------------------------------------------------ Layer 2
;; Rule 2: Trust level inheritance

(deftest trust-level-inheritance-test
  (testing "Combines trusted packs as trusted"
    (let [pack-a (trust/make-pack-ref "pack-a" :trusted :authority/instruction)
          pack-b (trust/make-pack-ref "pack-b" :trusted :authority/data)
          result (trust/compute-inherited-trust-level [pack-a pack-b])]
      (is (= :trusted result))))

  (testing "Combining with untrusted yields untrusted"
    (let [pack-a (trust/make-pack-ref "pack-a" :trusted :authority/instruction)
          pack-b (trust/make-pack-ref "pack-b" :untrusted :authority/data)
          result (trust/compute-inherited-trust-level [pack-a pack-b])]
      (is (= :untrusted result))))

  (testing "Any tainted pack taints the entire combination"
    (let [pack-a (trust/make-pack-ref "pack-a" :trusted :authority/instruction)
          pack-b (trust/make-pack-ref "pack-b" :untrusted :authority/data)
          pack-c (trust/make-pack-ref "pack-c" :tainted :authority/data)
          result (trust/compute-inherited-trust-level [pack-a pack-b pack-c])]
      (is (= :tainted result))))

  (testing "Multiple untrusted packs remain untrusted"
    (let [pack-a (trust/make-pack-ref "pack-a" :untrusted :authority/data)
          pack-b (trust/make-pack-ref "pack-b" :untrusted :authority/data)
          result (trust/compute-inherited-trust-level [pack-a pack-b])]
      (is (= :untrusted result)))))

;------------------------------------------------------------------------------ Layer 2
;; Rule 3: Cross-trust references

(deftest cross-trust-references-valid-test
  (testing "Linear dependency chain is valid"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/instruction
                                               :dependencies ["pack-b"])
                 "pack-b" (trust/make-pack-ref "pack-b" :trusted :authority/data
                                               :dependencies ["pack-c"])
                 "pack-c" (trust/make-pack-ref "pack-c" :untrusted :authority/data)}
          result (trust/validate-cross-trust-references graph)]
      (is (:valid? result))
      (is (= graph (:graph result)))))

  (testing "DAG (diamond) is valid"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/instruction
                                               :dependencies ["pack-b" "pack-c"])
                 "pack-b" (trust/make-pack-ref "pack-b" :trusted :authority/data
                                               :dependencies ["pack-d"])
                 "pack-c" (trust/make-pack-ref "pack-c" :trusted :authority/data
                                               :dependencies ["pack-d"])
                 "pack-d" (trust/make-pack-ref "pack-d" :untrusted :authority/data)}
          result (trust/validate-cross-trust-references graph)]
      (is (:valid? result))))

  (testing "Self-contained pack is valid"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/instruction)}
          result (trust/validate-cross-trust-references graph)]
      (is (:valid? result)))))

(deftest cross-trust-references-circular-test
  (testing "INVALID: Direct circular dependency"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/data
                                               :dependencies ["pack-b"])
                 "pack-b" (trust/make-pack-ref "pack-b" :trusted :authority/data
                                               :dependencies ["pack-a"])}
          result (trust/validate-cross-trust-references graph)]
      (is (not (:valid? result)))
      (is (string? (:error result)))
      (is (re-find #"circular dependency" (clojure.string/lower-case (:error result))))))

  (testing "INVALID: Indirect circular dependency (A -> B -> C -> A)"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/data
                                               :dependencies ["pack-b"])
                 "pack-b" (trust/make-pack-ref "pack-b" :trusted :authority/data
                                               :dependencies ["pack-c"])
                 "pack-c" (trust/make-pack-ref "pack-c" :trusted :authority/data
                                               :dependencies ["pack-a"])}
          result (trust/validate-cross-trust-references graph)]
      (is (not (:valid? result)))
      (is (re-find #"circular" (clojure.string/lower-case (:error result)))))))

(deftest cross-trust-references-missing-dep-test
  (testing "INVALID: Missing dependency"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/instruction
                                               :dependencies ["pack-missing"])}
          result (trust/validate-cross-trust-references graph)]
      (is (not (:valid? result)))
      (is (re-find #"missing" (clojure.string/lower-case (:error result)))))))

;------------------------------------------------------------------------------ Layer 2
;; Rule 4: Tainted isolation

(deftest tainted-isolation-test
  (testing "Data pack with tainted dependency is OK"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/data
                                               :dependencies ["pack-b"])
                 "pack-b" (trust/make-pack-ref "pack-b" :tainted :authority/data)}
          result (trust/validate-tainted-isolation "pack-a" graph)]
      (is (:valid? result))))

  (testing "INVALID: Instruction pack with direct tainted dependency"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/instruction
                                               :dependencies ["pack-b"])
                 "pack-b" (trust/make-pack-ref "pack-b" :tainted :authority/data)}
          result (trust/validate-tainted-isolation "pack-a" graph)]
      (is (not (:valid? result)))
      (is (string? (:error result)))
      (is (re-find #"tainted" (clojure.string/lower-case (:error result))))
      (is (vector? (:tainted-path result)))))

  (testing "INVALID: Instruction pack with transitive tainted dependency"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/instruction
                                               :dependencies ["pack-b"])
                 "pack-b" (trust/make-pack-ref "pack-b" :trusted :authority/data
                                               :dependencies ["pack-c"])
                 "pack-c" (trust/make-pack-ref "pack-c" :tainted :authority/data)}
          result (trust/validate-tainted-isolation "pack-a" graph)]
      (is (not (:valid? result)))
      (is (re-find #"tainted" (clojure.string/lower-case (:error result))))
      (is (vector? (:tainted-path result)))
      (is (some #{"pack-c"} (:tainted-path result)))))

  (testing "Instruction pack with only trusted/untrusted deps is OK"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/instruction
                                               :dependencies ["pack-b" "pack-c"])
                 "pack-b" (trust/make-pack-ref "pack-b" :trusted :authority/data)
                 "pack-c" (trust/make-pack-ref "pack-c" :untrusted :authority/data)}
          result (trust/validate-tainted-isolation "pack-a" graph)]
      (is (:valid? result)))))

;------------------------------------------------------------------------------ Layer 3
;; Combined validation

(deftest validate-transitive-trust-complete-test
  (testing "Valid pack graph passes all rules"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/instruction
                                               :dependencies ["pack-b"])
                 "pack-b" (trust/make-pack-ref "pack-b" :trusted :authority/data
                                               :dependencies ["pack-c"])
                 "pack-c" (trust/make-pack-ref "pack-c" :untrusted :authority/data)}
          result (trust/validate-transitive-trust graph)]
      (is (:valid? result))
      (is (= (set (keys graph)) (set (:packs result))))))

  (testing "INVALID: Fails on instruction authority transitivity"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/instruction
                                               :dependencies ["pack-b"])
                 "pack-b" (trust/make-pack-ref "pack-b" :untrusted :authority/instruction)}
          result (trust/validate-transitive-trust graph)]
      (is (not (:valid? result)))
      (is (seq (:errors result)))))

  (testing "INVALID: Fails on tainted isolation"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/instruction
                                               :dependencies ["pack-b"])
                 "pack-b" (trust/make-pack-ref "pack-b" :tainted :authority/data)}
          result (trust/validate-transitive-trust graph)]
      (is (not (:valid? result)))
      (is (seq (:errors result)))))

  (testing "INVALID: Fails on circular dependency"
    (let [graph {"pack-a" (trust/make-pack-ref "pack-a" :trusted :authority/data
                                               :dependencies ["pack-b"])
                 "pack-b" (trust/make-pack-ref "pack-b" :trusted :authority/data
                                               :dependencies ["pack-a"])}]
      (is (thrown? Exception
                   (trust/validate-transitive-trust graph))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run all tests
  (clojure.test/run-tests 'ai.miniforge.knowledge.trust-test)

  ;; Run specific test
  (instruction-authority-not-transitive-test)
  (trust-level-inheritance-test)
  (cross-trust-references-valid-test)
  (tainted-isolation-test)
  (validate-transitive-trust-complete-test)

  :leave-this-here)
