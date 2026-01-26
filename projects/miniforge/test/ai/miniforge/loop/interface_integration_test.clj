(ns ai.miniforge.loop.interface-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.loop.interface :as loop]))

(def test-task
  {:task/id (random-uuid)
   :task/type :implement})

(defn valid-artifact []
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn hello [] \"world\")"})

(defn make-generate-fn [artifact]
  (fn [_task _ctx]
    {:artifact artifact
     :tokens 100}))

(deftest full-inner-loop-integration-test
  (testing "full loop execution"
    (let [loop-state (loop/create-inner-loop test-task {:max-iterations 3})
          generate-fn (make-generate-fn (valid-artifact))
          gates (loop/minimal-gates)
          strategies (loop/default-strategies)
          result (loop/run-inner-loop loop-state generate-fn gates strategies {})]
      (is (:success result))
      (is (some? (:artifact result)))
      (is (map? (:metrics result)))
      (is (= :gates-passed (get-in result [:termination :reason]))))))
