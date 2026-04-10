(ns ai.miniforge.data-foundry.pipeline-pack.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.data-foundry.pipeline-pack.interface :as pp]
            [clojure.java.io :as io]))

(def ^:private simple-pack-dir
  (-> (io/resource "test-packs/simple-pack/pack.edn")
      io/file .getParentFile .getPath))

(deftest interface-load-test
  (testing "Load pack via interface"
    (let [result (pp/load-pack simple-pack-dir)]
      (is (:success? result))
      (is (= "simple-pack" (get-in result [:pack :pack/manifest :pack/id]))))))

(deftest interface-registry-test
  (testing "Full registry workflow via interface"
    (let [result (pp/load-pack simple-pack-dir)
          pack (:pack result)
          reg (pp/create-registry)]
      (pp/register-pack! reg pack)
      (is (= 1 (pp/pack-count reg)))
      (is (= ["simple-pack"] (pp/list-pack-ids reg)))
      (is (some? (pp/get-pack reg "simple-pack")))

      (pp/unregister-pack! reg "simple-pack")
      (is (= 0 (pp/pack-count reg))))))

(deftest interface-trust-test
  (testing "Trust validation via interface"
    (let [result (pp/load-pack simple-pack-dir)
          pack (:pack result)]
      (is (:valid? (pp/validate-pack-trust pack))))))
