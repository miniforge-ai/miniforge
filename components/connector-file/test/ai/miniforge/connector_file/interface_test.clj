(ns ai.miniforge.connector-file.interface-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [ai.miniforge.connector-file.interface :as file-conn]
            [ai.miniforge.connector.interface :as conn]))

;; ---------------------------------------------------------------------------
;; Test fixtures — temp directory with sample files
;; ---------------------------------------------------------------------------

(def ^:private test-dir "target/test-connector-file")

(def ^:private csv-content "id,name,value\n1,alpha,100\n2,beta,200\n3,gamma,300\n4,delta,400\n5,epsilon,500\n")

(def ^:private json-content
  [{:id 1 :name "alpha" :value 100}
   {:id 2 :name "beta"  :value 200}
   {:id 3 :name "gamma" :value 300}])

(def ^:private edn-content
  [{:id 1 :name "alpha" :value 100}
   {:id 2 :name "beta"  :value 200}])

(defn- setup-test-files []
  (.mkdirs (io/file test-dir))
  (spit (str test-dir "/test.csv") csv-content)
  (with-open [wtr (io/writer (str test-dir "/test.json"))]
    (json/generate-stream json-content wtr))
  (spit (str test-dir "/test.edn") (pr-str edn-content)))

(defn- cleanup-test-files []
  (doseq [f (.listFiles (io/file test-dir))]
    (.delete f))
  (.delete (io/file test-dir)))

(use-fixtures :each
  (fn [f]
    (setup-test-files)
    (try (f) (finally (cleanup-test-files)))))

;; ---------------------------------------------------------------------------
;; Connector metadata
;; ---------------------------------------------------------------------------

(deftest connector-metadata-test
  (testing "File connector metadata"
    (let [meta file-conn/connector-metadata]
      (is (= :bidirectional (:connector/type meta)))
      (is (= #{:cap/batch} (:connector/capabilities meta)))
      (is (= #{:none} (:connector/auth-methods meta))))))

;; ---------------------------------------------------------------------------
;; Connect / close lifecycle
;; ---------------------------------------------------------------------------

(deftest connect-close-lifecycle-test
  (testing "Connect and close lifecycle"
    (let [fc (file-conn/create-file-connector)
          connect-result (conn/connect fc {:file/path (str test-dir "/test.csv")
                                           :file/format :csv} {})]
      (is (= :connected (:connector/status connect-result)))
      (is (string? (:connection/handle connect-result)))
      (let [close-result (conn/close fc (:connection/handle connect-result))]
        (is (= :closed (:connector/status close-result)))))))

(deftest connect-missing-path-test
  (testing "Connect fails without :file/path"
    (let [fc (file-conn/create-file-connector)]
      (is (thrown? Exception (conn/connect fc {:file/format :csv} {}))))))

(deftest connect-missing-format-test
  (testing "Connect fails without :file/format"
    (let [fc (file-conn/create-file-connector)]
      (is (thrown? Exception (conn/connect fc {:file/path "foo.csv"} {}))))))

(deftest connect-unsupported-format-test
  (testing "Connect fails with unsupported format"
    (let [fc (file-conn/create-file-connector)]
      (is (thrown? Exception (conn/connect fc {:file/path "foo.xml"
                                               :file/format :xml} {}))))))

;; ---------------------------------------------------------------------------
;; Extract — CSV
;; ---------------------------------------------------------------------------

(deftest extract-csv-all-test
  (testing "Extract all records from CSV"
    (let [fc (file-conn/create-file-connector)
          handle (:connection/handle
                  (conn/connect fc {:file/path (str test-dir "/test.csv")
                                    :file/format :csv} {}))
          result (conn/extract fc handle "test" {})]
      (is (= 5 (:extract/row-count result)))
      (is (= 5 (count (:records result))))
      (is (false? (:extract/has-more result)))
      (is (= "1" (:id (first (:records result)))))
      (conn/close fc handle))))

(deftest extract-csv-pagination-test
  (testing "Extract with batch pagination"
    (let [fc (file-conn/create-file-connector)
          handle (:connection/handle
                  (conn/connect fc {:file/path (str test-dir "/test.csv")
                                    :file/format :csv} {}))
          ;; First batch of 2
          r1 (conn/extract fc handle "test" {:extract/batch-size 2})
          _ (is (= 2 (:extract/row-count r1)))
          _ (is (true? (:extract/has-more r1)))
          _ (is (= 2 (get-in r1 [:extract/cursor :cursor/value])))

          ;; Second batch of 2 using cursor
          r2 (conn/extract fc handle "test" {:extract/batch-size 2
                                              :extract/cursor (:extract/cursor r1)})
          _ (is (= 2 (:extract/row-count r2)))
          _ (is (true? (:extract/has-more r2)))

          ;; Third batch — remainder
          r3 (conn/extract fc handle "test" {:extract/batch-size 2
                                              :extract/cursor (:extract/cursor r2)})]
      (is (= 1 (:extract/row-count r3)))
      (is (false? (:extract/has-more r3)))
      (conn/close fc handle))))

;; ---------------------------------------------------------------------------
;; Extract — JSON
;; ---------------------------------------------------------------------------

(deftest extract-json-test
  (testing "Extract all records from JSON"
    (let [fc (file-conn/create-file-connector)
          handle (:connection/handle
                  (conn/connect fc {:file/path (str test-dir "/test.json")
                                    :file/format :json} {}))
          result (conn/extract fc handle "test" {})]
      (is (= 3 (:extract/row-count result)))
      (is (= 1 (:id (first (:records result)))))
      (conn/close fc handle))))

;; ---------------------------------------------------------------------------
;; Extract — EDN
;; ---------------------------------------------------------------------------

(deftest extract-edn-test
  (testing "Extract all records from EDN"
    (let [fc (file-conn/create-file-connector)
          handle (:connection/handle
                  (conn/connect fc {:file/path (str test-dir "/test.edn")
                                    :file/format :edn} {}))
          result (conn/extract fc handle "test" {})]
      (is (= 2 (:extract/row-count result)))
      (is (= "alpha" (:name (first (:records result)))))
      (conn/close fc handle))))

;; ---------------------------------------------------------------------------
;; Extract — file not found
;; ---------------------------------------------------------------------------

(deftest extract-file-not-found-test
  (testing "Extract fails when file does not exist"
    (let [fc (file-conn/create-file-connector)
          handle (:connection/handle
                  (conn/connect fc {:file/path (str test-dir "/nonexistent.csv")
                                    :file/format :csv} {}))]
      (is (thrown? Exception (conn/extract fc handle "test" {})))
      (conn/close fc handle))))

;; ---------------------------------------------------------------------------
;; Publish — JSON overwrite
;; ---------------------------------------------------------------------------

(deftest publish-json-overwrite-test
  (testing "Publish records to JSON file (overwrite mode)"
    (let [fc (file-conn/create-file-connector)
          out-path (str test-dir "/output.json")
          handle (:connection/handle
                  (conn/connect fc {:file/path out-path
                                    :file/format :json} {}))
          records [{:id 10 :name "x"} {:id 20 :name "y"}]
          result (conn/publish fc handle "out" records {:publish/mode :overwrite})]
      (is (= 2 (:publish/records-written result)))
      (is (= 0 (:publish/records-failed result)))
      ;; Verify file contents
      (let [written (with-open [rdr (io/reader out-path)]
                      (json/parse-stream rdr true))]
        (is (= 2 (count written)))
        (is (= 10 (:id (first written)))))
      (conn/close fc handle))))

;; ---------------------------------------------------------------------------
;; Publish — JSON append
;; ---------------------------------------------------------------------------

(deftest publish-json-append-test
  (testing "Publish records to JSON file (append mode)"
    (let [fc (file-conn/create-file-connector)
          out-path (str test-dir "/append.json")
          ;; First write
          h1 (:connection/handle
              (conn/connect fc {:file/path out-path :file/format :json} {}))
          _ (conn/publish fc h1 "out" [{:id 1}] {:publish/mode :overwrite})
          _ (conn/close fc h1)
          ;; Append
          h2 (:connection/handle
              (conn/connect fc {:file/path out-path :file/format :json} {}))
          result (conn/publish fc h2 "out" [{:id 2}] {:publish/mode :append})]
      (is (= 1 (:publish/records-written result)))
      ;; Verify both records present
      (let [written (with-open [rdr (io/reader out-path)]
                      (json/parse-stream rdr true))]
        (is (= 2 (count written))))
      (conn/close fc h2))))

;; ---------------------------------------------------------------------------
;; Publish — EDN
;; ---------------------------------------------------------------------------

(deftest publish-edn-test
  (testing "Publish records to EDN file"
    (let [fc (file-conn/create-file-connector)
          out-path (str test-dir "/output.edn")
          handle (:connection/handle
                  (conn/connect fc {:file/path out-path
                                    :file/format :edn} {}))
          records [{:id 1 :val "a"} {:id 2 :val "b"}]
          result (conn/publish fc handle "out" records {:publish/mode :overwrite})]
      (is (= 2 (:publish/records-written result)))
      (let [written (read-string (slurp out-path))]
        (is (= 2 (count written))))
      (conn/close fc handle))))

;; ---------------------------------------------------------------------------
;; Discover
;; ---------------------------------------------------------------------------

(deftest discover-test
  (testing "Discover returns schema info for connected file"
    (let [fc (file-conn/create-file-connector)
          handle (:connection/handle
                  (conn/connect fc {:file/path (str test-dir "/test.csv")
                                    :file/format :csv} {}))
          result (conn/discover fc handle {})]
      (is (= 1 (:discover/total-count result)))
      (is (= "test.csv" (:schema/name (first (:schemas result)))))
      (conn/close fc handle))))

;; ---------------------------------------------------------------------------
;; Checkpoint
;; ---------------------------------------------------------------------------

(deftest checkpoint-test
  (testing "Checkpoint persists cursor state"
    (let [fc (file-conn/create-file-connector)
          handle (:connection/handle
                  (conn/connect fc {:file/path (str test-dir "/test.csv")
                                    :file/format :csv} {}))
          cursor {:cursor/type :offset :cursor/value 5}
          result (conn/checkpoint fc handle "file-conn-1" cursor)]
      (is (= :committed (:checkpoint/status result)))
      (is (uuid? (:checkpoint/id result)))
      (conn/close fc handle))))
