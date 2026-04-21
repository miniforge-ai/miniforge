(ns ai.miniforge.pipeline-pack.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.pipeline-pack.registry :as registry]))

(def ^:private sample-pack
  {:pack/manifest {:pack/id "test-pack"
                   :pack/name "Test Pack"
                   :pack/trust-level :untrusted
                   :pack/authority :authority/data}
   :pack/dir "/tmp/test"
   :pack/pipelines ["/tmp/test/p.edn"]
   :pack/envs ["/tmp/test/e.edn"]})

(def ^:private trusted-instruction-pack
  {:pack/manifest {:pack/id "trusted-instr"
                   :pack/name "Trusted Instruction Pack"
                   :pack/trust-level :trusted
                   :pack/authority :authority/instruction}
   :pack/dir "/tmp/trusted"
   :pack/pipelines []
   :pack/envs []})

(def ^:private untrusted-instruction-pack
  {:pack/manifest {:pack/id "bad-pack"
                   :pack/name "Bad Pack"
                   :pack/trust-level :untrusted
                   :pack/authority :authority/instruction}
   :pack/dir "/tmp/bad"
   :pack/pipelines []
   :pack/envs []})

;; -- Registry CRUD --

(deftest registry-lifecycle-test
  (testing "Create empty registry"
    (let [reg (registry/create-registry)]
      (is (= 0 (registry/pack-count reg)))))

  (testing "Register and retrieve pack"
    (let [reg (registry/create-registry)]
      (registry/register-pack! reg sample-pack)
      (is (= 1 (registry/pack-count reg)))
      (let [retrieved (registry/get-pack reg "test-pack")]
        (is (some? retrieved))
        (is (= "test-pack" (get-in retrieved [:pack/manifest :pack/id]))))))

  (testing "List packs and ids"
    (let [reg (registry/create-registry)]
      (registry/register-pack! reg sample-pack)
      (registry/register-pack! reg trusted-instruction-pack)
      (is (= 2 (registry/pack-count reg)))
      (is (= #{"test-pack" "trusted-instr"} (set (registry/list-pack-ids reg))))
      (is (= 2 (count (registry/list-packs reg))))))

  (testing "Unregister pack"
    (let [reg (registry/create-registry)]
      (registry/register-pack! reg sample-pack)
      (registry/unregister-pack! reg "test-pack")
      (is (= 0 (registry/pack-count reg)))
      (is (nil? (registry/get-pack reg "test-pack")))))

  (testing "Get nonexistent pack returns nil"
    (let [reg (registry/create-registry)]
      (is (nil? (registry/get-pack reg "nope"))))))

;; -- Trust validation --

(deftest trust-validation-test
  (testing "Data-only + untrusted is valid"
    (let [result (registry/validate-pack-trust sample-pack)]
      (is (:valid? result))))

  (testing "Instruction + trusted is valid"
    (let [result (registry/validate-pack-trust trusted-instruction-pack)]
      (is (:valid? result))))

  (testing "Instruction + untrusted is invalid"
    (let [result (registry/validate-pack-trust untrusted-instruction-pack)]
      (is (not (:valid? result)))
      (is (some? (:error result)))))

  (testing "Data-only + tainted is valid (data packs work at any trust)"
    (let [tainted-data {:pack/manifest {:pack/id "tainted"
                                        :pack/trust-level :tainted
                                        :pack/authority :authority/data}}]
      (is (:valid? (registry/validate-pack-trust tainted-data))))))
