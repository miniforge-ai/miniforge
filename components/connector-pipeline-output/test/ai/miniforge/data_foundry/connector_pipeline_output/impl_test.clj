(ns ai.miniforge.data-foundry.connector-pipeline-output.impl-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [ai.miniforge.data-foundry.connector-pipeline-output.impl :as impl]
            [ai.miniforge.data-foundry.connector-pipeline-output.schema :as schema]
            [ai.miniforge.data-foundry.connector-pipeline-output.interface :as iface]))

(def ^:private test-dir "target/test-output")

(defn- clean-test-dir [f]
  (let [dir (io/file test-dir)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))
    (f)
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))))

(use-fixtures :each clean-test-dir)

(deftest connect-requires-dir
  (testing "connect throws without :output/dir"
    (is (thrown? Exception (impl/do-connect {})))))

(deftest connect-rejects-unsupported-format
  (testing "connect throws on unsupported format"
    (is (thrown? Exception
                 (impl/do-connect {:output/dir test-dir
                                   :output/format :xml})))))

(deftest connect-close-lifecycle
  (testing "connect and close work with defaults"
    (let [result (impl/do-connect {:output/dir test-dir})
          handle (:connection/handle result)]
      (is (= :connected (:connector/status result)))
      (is (string? handle))
      (let [close-result (impl/do-close handle)]
        (is (= :closed (:connector/status close-result)))))))

(deftest publish-edn-test
  (testing "publish writes manifest.edn and records.edn"
    (let [run-id "test-run-001"
          config {:output/dir test-dir
                  :output/format :edn
                  :output/run-id run-id
                  :output/pipeline-name "test-pipeline"}
          handle (:connection/handle (impl/do-connect config))
          records [{:id 1 :name "alpha"} {:id 2 :name "beta"}]
          result (impl/do-publish handle records {})]

      (is (= 2 (:publish/records-written result)))
      (is (= 0 (:publish/records-failed result)))

      ;; Verify manifest
      (let [manifest (edn/read-string (slurp (str test-dir "/" run-id "/manifest.edn")))]
        (is (= "1.0" (:manifest/version manifest)))
        (is (= run-id (:manifest/run-id manifest)))
        (is (= "test-pipeline" (:manifest/pipeline-name manifest)))
        (is (= :edn (:manifest/format manifest)))
        (is (= 2 (:manifest/record-count manifest)))
        (is (= "records.edn" (:manifest/records-file manifest)))
        (is (string? (:manifest/created-at manifest))))

      ;; Verify records
      (let [written-records (edn/read-string (slurp (str test-dir "/" run-id "/records.edn")))]
        (is (= 2 (count written-records)))
        (is (= "alpha" (:name (first written-records)))))

      (impl/do-close handle))))

(deftest publish-json-test
  (testing "publish writes manifest.edn and records.json"
    (let [run-id "test-run-json"
          config {:output/dir test-dir
                  :output/format :json
                  :output/run-id run-id}
          handle (:connection/handle (impl/do-connect config))
          records [{:x 1} {:x 2} {:x 3}]
          result (impl/do-publish handle records {})]

      (is (= 3 (:publish/records-written result)))

      ;; Manifest is always EDN
      (let [manifest (edn/read-string (slurp (str test-dir "/" run-id "/manifest.edn")))]
        (is (= :json (:manifest/format manifest)))
        (is (= "records.json" (:manifest/records-file manifest))))

      ;; Records are JSON
      (let [written (with-open [rdr (io/reader (str test-dir "/" run-id "/records.json"))]
                      (json/read rdr :key-fn keyword))]
        (is (= 3 (count written)))
        (is (= 1 (:x (first written)))))

      (impl/do-close handle))))

(deftest publish-default-format-test
  (testing "defaults to EDN when no format specified"
    (let [run-id "test-default"
          config {:output/dir test-dir :output/run-id run-id}
          handle (:connection/handle (impl/do-connect config))
          _ (impl/do-publish handle [{:a 1}] {})
          manifest (edn/read-string (slurp (str test-dir "/" run-id "/manifest.edn")))]
      (is (= :edn (:manifest/format manifest)))
      (impl/do-close handle))))

(deftest publish-auto-run-id-test
  (testing "auto-generates run-id when not provided"
    (let [config {:output/dir test-dir}
          handle (:connection/handle (impl/do-connect config))
          _ (impl/do-publish handle [{:a 1}] {})
          ;; Find the generated run-id directory
          run-dirs (.listFiles (io/file test-dir))]
      (is (= 1 (count run-dirs)))
      (is (.exists (io/file (first run-dirs) "manifest.edn")))
      (impl/do-close handle))))

(deftest publish-writes-json-schema-test
  (testing "publish writes manifest.schema.json alongside manifest"
    (let [run-id "test-json-schema"
          config {:output/dir test-dir :output/run-id run-id}
          handle (:connection/handle (impl/do-connect config))
          _ (impl/do-publish handle [{:a 1}] {})
          schema-file (io/file (str test-dir "/" run-id "/manifest.schema.json"))]
      (is (.exists schema-file))
      (let [json-schema (with-open [rdr (io/reader schema-file)]
                          (json/read rdr :key-fn keyword))]
        (is (= "object" (:type json-schema)))
        (is (contains? (:properties json-schema) (keyword "manifest/version"))))
      (impl/do-close handle))))

(deftest schema-validation-test
  (testing "valid config passes validation"
    (let [result (schema/validate-config {:output/dir "output/"})]
      (is (true? (:valid? result)))))

  (testing "invalid config fails validation"
    (let [result (schema/validate-config {})]
      (is (false? (:valid? result)))
      (is (some? (:errors result)))))

  (testing "valid manifest passes validation"
    (let [manifest {:manifest/version "1.0"
                    :manifest/run-id "run-001"
                    :manifest/format :edn
                    :manifest/record-count 10
                    :manifest/created-at "2026-03-25T00:00:00Z"
                    :manifest/records-file "records.edn"}
          result (schema/validate-manifest manifest)]
      (is (true? (:valid? result)))))

  (testing "invalid manifest fails validation"
    (let [result (schema/validate-manifest {:manifest/version "2.0"})]
      (is (false? (:valid? result))))))

(deftest publish-per-dataset-test
  (testing "publish writes separate files per dataset when datasets provided"
    (let [run-id "test-datasets"
          config {:output/dir test-dir :output/format :edn :output/run-id run-id}
          handle (:connection/handle (impl/do-connect config))
          all-records [{:type "pr" :n 1} {:type "pr" :n 2}
                       {:type "issue" :n 3} {:type "issue" :n 4} {:type "issue" :n 5}]
          datasets {"Ingest PRs" [{:type "pr" :n 1} {:type "pr" :n 2}]
                    "Ingest Issues" [{:type "issue" :n 3} {:type "issue" :n 4} {:type "issue" :n 5}]}
          result (impl/do-publish handle all-records {:publish/datasets datasets})]

      (is (= 5 (:publish/records-written result)))

      ;; Combined records file exists
      (is (.exists (io/file (str test-dir "/" run-id "/records.edn"))))

      ;; Per-dataset files exist
      (is (.exists (io/file (str test-dir "/" run-id "/records-ingest_prs.edn"))))
      (is (.exists (io/file (str test-dir "/" run-id "/records-ingest_issues.edn"))))

      ;; Per-dataset files have correct record counts
      (let [pr-records (edn/read-string (slurp (str test-dir "/" run-id "/records-ingest_prs.edn")))
            issue-records (edn/read-string (slurp (str test-dir "/" run-id "/records-ingest_issues.edn")))]
        (is (= 2 (count pr-records)))
        (is (= 5 (count (edn/read-string (slurp (str test-dir "/" run-id "/records.edn"))))))
        (is (= 3 (count issue-records))))

      ;; Manifest includes datasets
      (let [manifest (edn/read-string (slurp (str test-dir "/" run-id "/manifest.edn")))]
        (is (= 5 (:manifest/record-count manifest)))
        (is (= 2 (count (:manifest/datasets manifest))))
        (is (every? :filename (:manifest/datasets manifest)))
        (is (every? :count (:manifest/datasets manifest))))

      (impl/do-close handle))))

(deftest publish-single-dataset-uses-combined-test
  (testing "single dataset falls back to combined output (no per-dataset files)"
    (let [run-id "test-single-ds"
          config {:output/dir test-dir :output/format :edn :output/run-id run-id}
          handle (:connection/handle (impl/do-connect config))
          datasets {"Only Stage" [{:a 1}]}
          result (impl/do-publish handle [{:a 1}] {:publish/datasets datasets})]

      (is (= 1 (:publish/records-written result)))
      (is (.exists (io/file (str test-dir "/" run-id "/records.edn"))))
      ;; No per-dataset file for single dataset
      (let [manifest (edn/read-string (slurp (str test-dir "/" run-id "/manifest.edn")))]
        (is (nil? (:manifest/datasets manifest))))
      (impl/do-close handle))))

(deftest interface-exposes-schemas-test
  (testing "interface exposes Malli schemas"
    (is (some? iface/Manifest))
    (is (some? iface/OutputConfig)))

  (testing "interface exposes JSON Schema generators"
    (let [manifest-js (iface/manifest-json-schema)
          config-js (iface/config-json-schema)]
      (is (= "object" (:type manifest-js)))
      (is (= "object" (:type config-js)))
      (is (contains? (:properties manifest-js) (keyword "manifest/version")))
      (is (contains? (:properties config-js) (keyword "output/dir")))))

  (testing "interface exposes validation helpers"
    (is (true? (:valid? (iface/validate-config {:output/dir "x"}))))
    (is (false? (:valid? (iface/validate-config {}))))))
