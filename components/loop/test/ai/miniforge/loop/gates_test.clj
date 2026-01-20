(ns ai.miniforge.loop.gates-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.loop.gates :as gates]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def valid-code-artifact
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn hello [] \"world\")"})

(def invalid-syntax-artifact
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn hello ["})

(def artifact-with-println
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn hello [] (println \"debug\") :ok)"})

(def artifact-with-secret
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(def api-key = \"sk-12345abcdef\")"})

(def non-code-artifact
  {:artifact/id (random-uuid)
   :artifact/type :spec
   :artifact/content {:description "A spec document"}})

;------------------------------------------------------------------------------ Layer 1
;; Gate protocol tests

(deftest syntax-gate-test
  (let [gate (gates/syntax-gate)]
    (testing "gate-id returns correct id"
      (is (= :syntax-check (gates/gate-id gate))))

    (testing "gate-type returns :syntax"
      (is (= :syntax (gates/gate-type gate))))

    (testing "valid syntax passes"
      (let [result (gates/check gate valid-code-artifact {})]
        (is (:gate/passed? result))
        (is (= :syntax-check (:gate/id result)))
        (is (= :syntax (:gate/type result)))))

    (testing "invalid syntax fails"
      (let [result (gates/check gate invalid-syntax-artifact {})]
        (is (not (:gate/passed? result)))
        (is (seq (:gate/errors result)))
        (is (= :syntax-error (:code (first (:gate/errors result)))))))

    (testing "non-code artifacts pass"
      (let [result (gates/check gate non-code-artifact {})]
        (is (:gate/passed? result))))))

(deftest lint-gate-test
  (let [gate (gates/lint-gate :my-lint {})]
    (testing "gate-id returns correct id"
      (is (= :my-lint (gates/gate-id gate))))

    (testing "gate-type returns :lint"
      (is (= :lint (gates/gate-type gate))))

    (testing "clean code passes"
      (let [result (gates/check gate valid-code-artifact {})]
        (is (:gate/passed? result))))

    (testing "code with println has warning"
      (let [result (gates/check gate artifact-with-println {})]
        ;; Passes by default but has warning
        (is (:gate/passed? result))
        (is (seq (:gate/warnings result)))))

    (testing "fail-on-warning causes failure"
      (let [strict-gate (gates/lint-gate :strict {:fail-on-warning? true})
            result (gates/check strict-gate artifact-with-println {})]
        (is (not (:gate/passed? result)))))))

(deftest policy-gate-test
  (let [gate (gates/policy-gate :security {:policies [:no-secrets]})]
    (testing "gate-id returns correct id"
      (is (= :security (gates/gate-id gate))))

    (testing "gate-type returns :policy"
      (is (= :policy (gates/gate-type gate))))

    (testing "clean code passes"
      (let [result (gates/check gate valid-code-artifact {})]
        (is (:gate/passed? result))))

    (testing "code with secret fails"
      (let [result (gates/check gate artifact-with-secret {})]
        (is (not (:gate/passed? result)))
        (is (= :hardcoded-secret (:code (first (:gate/errors result)))))))

    (testing "no-todos policy works"
      (let [todo-gate (gates/policy-gate :todos {:policies [:no-todos]})
            artifact-with-todo {:artifact/id (random-uuid)
                                :artifact/type :code
                                :artifact/content "; TODO: fix this later\n(defn x [] :ok)"}
            result (gates/check todo-gate artifact-with-todo {})]
        (is (not (:gate/passed? result)))
        (is (= :todo-found (:code (first (:gate/errors result)))))))))

(deftest test-gate-test
  (testing "test gate with no test-fn passes with warning"
    (let [gate (gates/test-gate)
          result (gates/check gate valid-code-artifact {})]
      (is (:gate/passed? result))
      (is (seq (:gate/warnings result)))))

  (testing "test gate with passing test-fn"
    (let [gate (gates/test-gate :my-test
                                {:test-fn (fn [_artifact _ctx]
                                            {:passed? true})})
          result (gates/check gate valid-code-artifact {})]
      (is (:gate/passed? result))))

  (testing "test gate with failing test-fn"
    (let [gate (gates/test-gate :my-test
                                {:test-fn (fn [_artifact _ctx]
                                            {:passed? false
                                             :errors [{:code :test-failed
                                                       :message "Unit test failed"}]})})
          result (gates/check gate valid-code-artifact {})]
      (is (not (:gate/passed? result)))
      (is (= :test-failed (:code (first (:gate/errors result))))))))

(deftest custom-gate-test
  (testing "custom gate with passing check"
    (let [gate (gates/custom-gate :length-check
                                  (fn [_artifact _ctx]
                                    (gates/pass-result :length-check :custom)))
          result (gates/check gate valid-code-artifact {})]
      (is (:gate/passed? result))
      (is (= :length-check (:gate/id result)))))

  (testing "custom gate with failing check"
    (let [gate (gates/custom-gate :length-check
                                  (fn [artifact _ctx]
                                    (if (> (count (:artifact/content artifact "")) 10)
                                      (gates/fail-result :length-check :custom
                                                         [(gates/make-error :too-long "Content too long")])
                                      (gates/pass-result :length-check :custom))))
          result (gates/check gate valid-code-artifact {})]
      (is (not (:gate/passed? result))))))

;------------------------------------------------------------------------------ Layer 2
;; Gate runner tests

(deftest run-gates-test
  (testing "all gates pass"
    (let [gates [(gates/syntax-gate)
                 (gates/lint-gate)]
          result (gates/run-gates gates valid-code-artifact {})]
      (is (:passed? result))
      (is (= 2 (count (:results result))))
      (is (empty? (:failed-gates result)))))

  (testing "one gate fails"
    (let [gates [(gates/syntax-gate)
                 (gates/policy-gate :sec {:policies [:no-secrets]})]
          result (gates/run-gates gates artifact-with-secret {})]
      (is (not (:passed? result)))
      (is (= [:sec] (:failed-gates result)))
      (is (seq (:errors result)))))

  (testing "fail-fast stops on first failure"
    (let [call-count (atom 0)
          slow-gate (gates/custom-gate :slow
                                       (fn [_a _c]
                                         (swap! call-count inc)
                                         (gates/pass-result :slow :custom)))
          gates [(gates/syntax-gate)  ; will fail
                 slow-gate]
          result (gates/run-gates gates invalid-syntax-artifact {}
                                  :fail-fast? true)]
      (is (not (:passed? result)))
      (is (= 0 @call-count)))))  ; slow-gate should not be called

(deftest gate-set-constructors-test
  (testing "default-gates returns expected gates"
    (let [gates (gates/default-gates)]
      (is (= 3 (count gates)))
      (is (some #(= :syntax (gates/gate-type %)) gates))
      (is (some #(= :lint (gates/gate-type %)) gates))
      (is (some #(= :policy (gates/gate-type %)) gates))))

  (testing "minimal-gates returns only syntax"
    (let [gates (gates/minimal-gates)]
      (is (= 1 (count gates)))
      (is (= :syntax (gates/gate-type (first gates))))))

  (testing "strict-gates returns all gates with strict config"
    (let [gates (gates/strict-gates)]
      (is (= 3 (count gates))))))

;------------------------------------------------------------------------------ Layer 2
;; Result constructor tests

(deftest result-constructors-test
  (testing "pass-result creates passing result"
    (let [result (gates/pass-result :my-gate :syntax)]
      (is (:gate/passed? result))
      (is (= :my-gate (:gate/id result)))
      (is (= :syntax (:gate/type result)))))

  (testing "fail-result creates failing result"
    (let [errors [(gates/make-error :some-error "Error message")]
          result (gates/fail-result :my-gate :lint errors)]
      (is (not (:gate/passed? result)))
      (is (= errors (:gate/errors result)))))

  (testing "make-error creates error map"
    (let [error (gates/make-error :code "message" :file "test.clj" :line 10)]
      (is (= :code (:code error)))
      (is (= "message" (:message error)))
      (is (= {:file "test.clj" :line 10} (:location error))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.loop.gates-test)

  :leave-this-here)
