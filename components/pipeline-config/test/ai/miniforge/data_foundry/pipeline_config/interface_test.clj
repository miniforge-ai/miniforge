(ns ai.miniforge.data-foundry.pipeline-config.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [ai.miniforge.data-foundry.pipeline-config.interface :as pc]
            [ai.miniforge.data-foundry.pipeline-config.env :as env]
            [ai.miniforge.data-foundry.data-quality.interface :as dq])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Loader tests
;; ---------------------------------------------------------------------------

(deftest load-pipeline-from-classpath-test
  (testing "loads pipeline EDN from classpath resource"
    (let [result (pc/load-pipeline "pipelines/financial-filings-etl.edn")]
      (is (:success? result))
      (is (= "Financial Filings ETL" (get-in result [:pipeline :pipeline/name])))
      (is (= 5 (count (get-in result [:pipeline :pipeline/stages])))))))

(deftest load-pipeline-missing-file-test
  (testing "returns schema/failure with anomaly for missing file"
    (let [result (pc/load-pipeline "nonexistent/pipeline.edn")]
      (is (not (:success? result)))
      (is (nil? (:pipeline result)))
      (is (some? (:error result)))
      (is (some? (:anomaly result))))))

(deftest load-pipeline-from-file-path-test
  (testing "loads pipeline EDN from absolute file path"
    (let [f (io/file "test/resources/pipelines/financial-filings-etl.edn")]
      (when (.exists f)
        (let [result (pc/load-pipeline (.getAbsolutePath f))]
          (is (:success? result))
          (is (= "Financial Filings ETL" (get-in result [:pipeline :pipeline/name]))))))))

;; ---------------------------------------------------------------------------
;; Resolution context factory tests
;; ---------------------------------------------------------------------------

(deftest create-resolution-context-defaults-test
  (testing "factory provides defaults for missing keys"
    (let [ctx (pc/create-resolution-context {})]
      (is (= {} (:connector-refs ctx)))
      (is (= {} (:dataset-refs ctx)))
      (is (= {} (:rule-registry ctx)))
      (is (= {} (:stage-configs ctx))))))

(deftest create-resolution-context-preserves-values-test
  (testing "factory preserves provided values"
    (let [conn-refs {:conn/src (UUID/randomUUID)}
          ctx (pc/create-resolution-context {:connector-refs conn-refs
                                             :stage-configs {"S1" {:k :v}}})]
      (is (= conn-refs (:connector-refs ctx)))
      (is (= {"S1" {:k :v}} (:stage-configs ctx))))))

;; ---------------------------------------------------------------------------
;; Resolver tests
;; ---------------------------------------------------------------------------

(deftest resolve-pipeline-assigns-uuids-test
  (testing "assigns pipeline ID and stage IDs"
    (let [edn {:pipeline/name "Test"
               :pipeline/version "1.0.0"
               :pipeline/mode :full-refresh
               :pipeline/stages [{:stage/name "S1"
                                  :stage/family :ingest
                                  :stage/input-datasets []
                                  :stage/output-datasets [:ds/out]
                                  :stage/dependencies []}]
               :pipeline/input-datasets []
               :pipeline/output-datasets []}
          ctx (pc/create-resolution-context {})
          resolved (pc/resolve-pipeline edn ctx)]
      (is (instance? UUID (:pipeline/id resolved)))
      (is (instance? UUID (get-in resolved [:pipeline/stages 0 :stage/id])))
      (is (some? (:pipeline/created-at resolved))))))

(deftest resolve-pipeline-resolves-connector-refs-test
  (testing "resolves symbolic connector refs to UUIDs"
    (let [conn-uuid (UUID/randomUUID)
          edn {:pipeline/name "Test"
               :pipeline/version "1.0.0"
               :pipeline/mode :full-refresh
               :pipeline/stages [{:stage/name "Ingest"
                                  :stage/family :ingest
                                  :stage/connector-ref :conn/src
                                  :stage/input-datasets []
                                  :stage/output-datasets [:ds/raw]
                                  :stage/dependencies []}]
               :pipeline/input-datasets []
               :pipeline/output-datasets []}
          ctx (pc/create-resolution-context {:connector-refs {:conn/src conn-uuid}})
          resolved (pc/resolve-pipeline edn ctx)]
      (is (= conn-uuid (get-in resolved [:pipeline/stages 0 :stage/connector-ref]))))))

(deftest resolve-pipeline-resolves-dataset-refs-test
  (testing "resolves symbolic dataset refs to UUIDs"
    (let [ds-uuid (UUID/randomUUID)
          edn {:pipeline/name "Test"
               :pipeline/version "1.0.0"
               :pipeline/mode :full-refresh
               :pipeline/stages [{:stage/name "S1"
                                  :stage/family :ingest
                                  :stage/input-datasets [:ds/in]
                                  :stage/output-datasets [:ds/out]
                                  :stage/dependencies []}]
               :pipeline/input-datasets []
               :pipeline/output-datasets []}
          ctx (pc/create-resolution-context {:dataset-refs {:ds/in ds-uuid}})
          resolved (pc/resolve-pipeline edn ctx)]
      (is (= ds-uuid (get-in resolved [:pipeline/stages 0 :stage/input-datasets 0]))))))

(deftest resolve-pipeline-resolves-stage-dependencies-test
  (testing "resolves stage name dependencies to stage IDs"
    (let [edn {:pipeline/name "Test"
               :pipeline/version "1.0.0"
               :pipeline/mode :full-refresh
               :pipeline/stages [{:stage/name "A"
                                  :stage/family :ingest
                                  :stage/input-datasets []
                                  :stage/output-datasets [:ds/raw]
                                  :stage/dependencies []}
                                 {:stage/name "B"
                                  :stage/family :normalize
                                  :stage/input-datasets [:ds/raw]
                                  :stage/output-datasets [:ds/norm]
                                  :stage/dependencies ["A"]}]
               :pipeline/input-datasets []
               :pipeline/output-datasets []}
          ctx (pc/create-resolution-context {})
          resolved (pc/resolve-pipeline edn ctx)
          stage-a-id (get-in resolved [:pipeline/stages 0 :stage/id])
          stage-b-deps (get-in resolved [:pipeline/stages 1 :stage/dependencies])]
      (is (= [stage-a-id] stage-b-deps)))))

(deftest resolve-pipeline-merges-stage-configs-test
  (testing "merges environment-specific stage configs"
    (let [edn {:pipeline/name "Test"
               :pipeline/version "1.0.0"
               :pipeline/mode :full-refresh
               :pipeline/stages [{:stage/name "Ingest"
                                  :stage/family :ingest
                                  :stage/input-datasets []
                                  :stage/output-datasets [:ds/raw]
                                  :stage/dependencies []
                                  :stage/config {:base "value"}}]
               :pipeline/input-datasets []
               :pipeline/output-datasets []}
          ctx (pc/create-resolution-context {:stage-configs {"Ingest" {:file/path "/data/in.csv"}}})
          resolved (pc/resolve-pipeline edn ctx)]
      (is (= "/data/in.csv" (get-in resolved [:pipeline/stages 0 :stage/config :file/path])))
      (is (= "value" (get-in resolved [:pipeline/stages 0 :stage/config :base]))))))

(deftest resolve-pipeline-hydrates-quality-rules-test
  (testing "hydrates quality pack rule descriptors into live rules"
    (let [edn {:pipeline/name "Test"
               :pipeline/version "1.0.0"
               :pipeline/mode :full-refresh
               :pipeline/stages [{:stage/name "Validate"
                                  :stage/family :validate
                                  :stage/input-datasets [:ds/in]
                                  :stage/output-datasets [:ds/out]
                                  :stage/dependencies []
                                  :stage/config {:stage/quality-pack
                                                 {:pack/id :test-pack
                                                  :pack/rules [{:rule/type :required :rule/field :name}]}}}]
               :pipeline/input-datasets []
               :pipeline/output-datasets []}
          rule-reg (pc/create-rule-registry)
          ctx (pc/create-resolution-context {:rule-registry (pc/resolve-rules rule-reg)})
          resolved (pc/resolve-pipeline edn ctx)
          hydrated-rules (get-in resolved [:pipeline/stages 0 :stage/config
                                           :stage/quality-pack :pack/rules])]
      (is (= 1 (count hydrated-rules)))
      (is (map? (first hydrated-rules)))
      (is (= :required-name (:rule/id (first hydrated-rules)))))))

;; ---------------------------------------------------------------------------
;; Connector registry tests
;; ---------------------------------------------------------------------------

(deftest connector-registry-lifecycle-test
  (testing "register, list, and instantiate connectors"
    (let [registry (pc/create-connector-registry)
          factory  (fn [] {:type :mock-connector})
          metadata {:connector/name "Mock"}]
      (pc/register-connector! registry :mock factory metadata)

      ;; list
      (let [listed (pc/list-connectors registry)]
        (is (= {:connector/name "Mock"} (get listed :mock))))

      ;; instantiate
      (let [result (pc/instantiate-connectors registry {:conn/src :mock})]
        (is (instance? UUID (get-in result [:connector-refs :conn/src])))
        (let [uuid (get-in result [:connector-refs :conn/src])
              inst (get-in result [:connectors uuid])]
          (is (= {:type :mock-connector} inst)))))))

(deftest connector-registry-missing-type-test
  (testing "instantiate throws for unregistered type"
    (let [registry (pc/create-connector-registry)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (pc/instantiate-connectors registry {:conn/src :unknown}))))))

;; ---------------------------------------------------------------------------
;; Rule registry tests
;; ---------------------------------------------------------------------------

(deftest rule-registry-builtin-types-test
  (testing "built-in rule resolvers are present"
    (let [registry (pc/create-rule-registry)
          rules (pc/resolve-rules registry)]
      (is (fn? (get rules :required)))
      (is (fn? (get rules :type-check)))
      (is (fn? (get rules :range)))
      (is (fn? (get rules :pattern))))))

(deftest rule-registry-custom-rule-test
  (testing "custom rules can be registered and resolved"
    (let [registry (pc/create-rule-registry)
          custom-rule (dq/custom-rule :my-rule (fn [_] {:valid? true}) :severity :warning)]
      (pc/register-rule! registry :my-rule custom-rule)
      (let [rules (pc/resolve-rules registry)]
        (is (= custom-rule (get rules :my-rule)))))))

;; ---------------------------------------------------------------------------
;; Environment config tests
;; ---------------------------------------------------------------------------

(deftest env-config-extract-test
  (testing "extracts connector types and stage configs from env config"
    (let [env-config {:env/name "test"
                      :env/connectors {:conn/src {:connector/type :file}
                                       :conn/api {:connector/type :http}}
                      :env/stages {"Ingest" {:file/path "/data/in.csv"}}}]
      (is (= {:conn/src :file :conn/api :http}
             (pc/extract-connector-types env-config)))
      (is (= {"Ingest" {:file/path "/data/in.csv"}}
             (pc/extract-stage-configs env-config))))))

;; ---------------------------------------------------------------------------
;; Environment variable interpolation tests
;; ---------------------------------------------------------------------------

(deftest resolve-env-vars-string-test
  (testing "resolves known env vars in strings"
    ;; PATH is always set
    (let [result (env/resolve-env-vars "${PATH}")]
      (is (string? result))
      (is (not= "${PATH}" result))
      (is (= (System/getenv "PATH") result)))))

(deftest resolve-env-vars-unknown-var-test
  (testing "leaves unknown env vars as-is"
    (let [result (env/resolve-env-vars "${DEFINITELY_NOT_SET_XYZ_12345}")]
      (is (= "${DEFINITELY_NOT_SET_XYZ_12345}" result)))))

(deftest resolve-env-vars-nested-map-test
  (testing "walks nested maps resolving env vars in all string values"
    (let [config {:api-key "${PATH}"
                  :nested {:url "https://example.com/${PATH}/data"}
                  :number 42
                  :keyword :keep-this}
          result (env/resolve-env-vars config)
          path-val (System/getenv "PATH")]
      (is (= path-val (:api-key result)))
      (is (= (str "https://example.com/" path-val "/data") (get-in result [:nested :url])))
      (is (= 42 (:number result)))
      (is (= :keep-this (:keyword result))))))

(deftest resolve-env-vars-in-vectors-test
  (testing "resolves env vars inside vectors"
    (let [result (env/resolve-env-vars [{:key "${PATH}"} "plain"])]
      (is (= (System/getenv "PATH") (:key (first result))))
      (is (= "plain" (second result))))))

(deftest resolve-env-vars-no-placeholders-test
  (testing "returns config unchanged when no placeholders present"
    (let [config {:url "https://example.com" :port 8080}]
      (is (= config (env/resolve-env-vars config))))))

;; ---------------------------------------------------------------------------
;; load-and-resolve integration test
;; ---------------------------------------------------------------------------

(deftest load-and-resolve-test
  (testing "loads and resolves a pipeline from classpath"
    (let [conn-uuid (UUID/randomUUID)
          ds-uuid   (UUID/randomUUID)
          rule-reg  (pc/create-rule-registry)
          ctx       (pc/create-resolution-context
                     {:connector-refs {:conn/file-src conn-uuid}
                      :dataset-refs   {:ds/raw ds-uuid}
                      :rule-registry  (pc/resolve-rules rule-reg)
                      :stage-configs  {"Ingest Filings" {:file/path "/tmp/test.csv"}}})
          result    (pc/load-and-resolve "pipelines/financial-filings-etl.edn" ctx)]
      (is (:success? result))
      (is (instance? UUID (get-in result [:pipeline :pipeline/id])))
      (is (= 5 (count (get-in result [:pipeline :pipeline/stages]))))
      ;; Verify connector ref was resolved
      (is (= conn-uuid (get-in result [:pipeline :pipeline/stages 0 :stage/connector-ref])))
      ;; Verify dataset ref was resolved
      (is (= ds-uuid (get-in result [:pipeline :pipeline/stages 0 :stage/output-datasets 0])))
      ;; Verify stage config was merged
      (is (= "/tmp/test.csv" (get-in result [:pipeline :pipeline/stages 0 :stage/config :file/path]))))))

(deftest load-and-resolve-missing-file-test
  (testing "returns schema/failure with anomaly for missing pipeline"
    (let [ctx (pc/create-resolution-context {})
          result (pc/load-and-resolve "nonexistent/pipeline.edn" ctx)]
      (is (not (:success? result)))
      (is (nil? (:pipeline result)))
      (is (some? (:anomaly result))))))
