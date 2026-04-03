(ns ai.miniforge.artifact.core-test
  "Unit tests for artifact core pure functions."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.artifact.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; build-artifact

(deftest build-artifact-minimal-test
  (testing "builds artifact with required fields only"
    (let [id (random-uuid)
          art (core/build-artifact {:id id
                                    :type :plan
                                    :version "1.0.0"
                                    :content {:steps ["a" "b"]}})]
      (is (= id (:artifact/id art)))
      (is (= :plan (:artifact/type art)))
      (is (= "1.0.0" (:artifact/version art)))
      (is (= {:steps ["a" "b"]} (:artifact/content art)))
      (is (= [] (:artifact/parents art)))
      (is (= {} (:artifact/metadata art)))
      (is (nil? (:artifact/origin art))))))

(deftest build-artifact-with-origin-test
  (testing "includes origin when provided"
    (let [art (core/build-artifact {:id (random-uuid)
                                    :type :code
                                    :version "2.0.0"
                                    :content "(defn foo [])"
                                    :origin :implementer})]
      (is (= :implementer (:artifact/origin art))))))

(deftest build-artifact-with-parents-test
  (testing "preserves provided parents"
    (let [p1 (random-uuid)
          p2 (random-uuid)
          art (core/build-artifact {:id (random-uuid)
                                    :type :code
                                    :version "1.0.0"
                                    :content ""
                                    :parents [p1 p2]})]
      (is (= [p1 p2] (:artifact/parents art))))))

(deftest build-artifact-with-metadata-test
  (testing "preserves provided metadata"
    (let [meta-map {:language :clojure :lines 42}
          art (core/build-artifact {:id (random-uuid)
                                    :type :code
                                    :version "1.0.0"
                                    :content ""
                                    :metadata meta-map})]
      (is (= meta-map (:artifact/metadata art))))))

(deftest build-artifact-nil-content-test
  (testing "nil content is preserved"
    (let [art (core/build-artifact {:id (random-uuid)
                                    :type :plan
                                    :version "1.0.0"
                                    :content nil})]
      (is (nil? (:artifact/content art))))))

;------------------------------------------------------------------------------ Layer 0
;; add-parent

(deftest add-parent-test
  (testing "appends parent ID to parents list"
    (let [base (core/build-artifact {:id (random-uuid)
                                     :type :code
                                     :version "1.0.0"
                                     :content ""})
          p1 (random-uuid)
          p2 (random-uuid)
          with-p1 (core/add-parent base p1)
          with-p1-p2 (core/add-parent with-p1 p2)]
      (is (= [p1] (:artifact/parents with-p1)))
      (is (= [p1 p2] (:artifact/parents with-p1-p2)))))

  (testing "add-parent on artifact without :artifact/parents initializes vector"
    (let [bare {:artifact/id (random-uuid) :artifact/type :test}
          result (core/add-parent bare (random-uuid))]
      (is (= 1 (count (:artifact/parents result)))))))

;------------------------------------------------------------------------------ Layer 0
;; add-child

(deftest add-child-test
  (testing "appends child ID to children list"
    (let [base (core/build-artifact {:id (random-uuid)
                                     :type :code
                                     :version "1.0.0"
                                     :content ""})
          c1 (random-uuid)
          c2 (random-uuid)
          with-c1 (core/add-child base c1)
          with-c1-c2 (core/add-child with-c1 c2)]
      (is (= [c1] (:artifact/children with-c1)))
      (is (= [c1 c2] (:artifact/children with-c1-c2)))))

  (testing "add-child on artifact without :artifact/children initializes vector"
    (let [bare {:artifact/id (random-uuid) :artifact/type :test}
          result (core/add-child bare (random-uuid))]
      (is (= 1 (count (:artifact/children result)))))))

;------------------------------------------------------------------------------ Layer 0
;; Immutability

(deftest build-artifact-immutability-test
  (testing "build-artifact does not mutate input map"
    (let [input {:id (random-uuid) :type :plan :version "1.0.0" :content {}}
          input-copy (into {} input)
          _ (core/build-artifact input)]
      (is (= input-copy input)))))

(deftest add-parent-immutability-test
  (testing "add-parent returns new map, original unchanged"
    (let [base (core/build-artifact {:id (random-uuid)
                                     :type :code
                                     :version "1.0.0"
                                     :content ""})
          original-parents (:artifact/parents base)
          _ (core/add-parent base (random-uuid))]
      (is (= [] original-parents))
      (is (= [] (:artifact/parents base))))))