(ns ai.miniforge.policy-pack.loader-test
  "Tests for the policy-pack loader."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.policy-pack.loader :as loader]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def ^:dynamic *test-dir* nil)

(defn create-test-dir
  "Create a temporary directory for test files."
  []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "policy-pack-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    dir))

(defn delete-dir
  "Recursively delete a directory."
  [dir]
  (when (.exists dir)
    (doseq [f (reverse (file-seq dir))]
      (.delete f))))

(defn test-fixture
  "Create and cleanup test directory."
  [f]
  (let [dir (create-test-dir)]
    (try
      (binding [*test-dir* dir]
        (f))
      (finally
        (delete-dir dir)))))

(use-fixtures :each test-fixture)

;------------------------------------------------------------------------------ Layer 1
;; Single file loader tests

(deftest load-pack-from-file-test
  (testing "Loads valid pack from EDN file"
    (let [pack-file (io/file *test-dir* "test.pack.edn")
          pack-content "{:pack/id \"test-pack\"
                        :pack/name \"Test Pack\"
                        :pack/version \"2026.01.22\"
                        :pack/description \"A test pack\"
                        :pack/author \"test\"
                        :pack/categories []
                        :pack/rules []
                        :pack/created-at #inst \"2026-01-22T00:00:00Z\"
                        :pack/updated-at #inst \"2026-01-22T00:00:00Z\"}"]
      (spit pack-file pack-content)

      (let [result (loader/load-pack-from-file (.getPath pack-file))]
        (is (:success? result))
        (is (= "test-pack" (get-in result [:pack :pack/id])))
        (is (= "Test Pack" (get-in result [:pack :pack/name]))))))

  (testing "Returns error for missing file"
    (let [result (loader/load-pack-from-file "/nonexistent/path.pack.edn")]
      (is (not (:success? result)))
      (is (seq (:errors result)))))

  (testing "Returns error for invalid EDN"
    (let [pack-file (io/file *test-dir* "invalid.pack.edn")]
      (spit pack-file "{:pack/id \"test\" :invalid")
      (let [result (loader/load-pack-from-file (.getPath pack-file))]
        (is (not (:success? result)))))))

(deftest load-pack-with-rules-test
  (testing "Loads pack with inline rules"
    (let [pack-file (io/file *test-dir* "with-rules.pack.edn")
          pack-content "{:pack/id \"rules-pack\"
                        :pack/name \"Rules Pack\"
                        :pack/version \"2026.01.22\"
                        :pack/description \"Pack with rules\"
                        :pack/author \"test\"
                        :pack/categories []
                        :pack/rules [{:rule/id :test-rule
                                     :rule/title \"Test Rule\"
                                     :rule/description \"A test rule\"
                                     :rule/severity :major
                                     :rule/category \"800\"
                                     :rule/applies-to {}
                                     :rule/detection {:type :content-scan
                                                     :pattern \"TODO\"}
                                     :rule/enforcement {:action :warn
                                                       :message \"Found TODO\"}}]
                        :pack/created-at #inst \"2026-01-22T00:00:00Z\"
                        :pack/updated-at #inst \"2026-01-22T00:00:00Z\"}"]
      (spit pack-file pack-content)

      (let [result (loader/load-pack-from-file (.getPath pack-file))]
        (is (:success? result))
        (is (= 1 (count (get-in result [:pack :pack/rules]))))
        (is (= :test-rule (get-in result [:pack :pack/rules 0 :rule/id])))))))

;------------------------------------------------------------------------------ Layer 2
;; Directory loader tests

(deftest load-pack-from-directory-test
  (testing "Loads pack from directory with manifest"
    (let [pack-dir (io/file *test-dir* "my-pack")]
      (.mkdirs pack-dir)
      (spit (io/file pack-dir "pack.edn")
            "{:pack/id \"dir-pack\"
              :pack/name \"Directory Pack\"
              :pack/version \"2026.01.22\"
              :pack/description \"Pack from directory\"
              :pack/author \"test\"
              :pack/categories []
              :pack/rules []
              :pack/created-at #inst \"2026-01-22T00:00:00Z\"
              :pack/updated-at #inst \"2026-01-22T00:00:00Z\"}")

      (let [result (loader/load-pack-from-directory (.getPath pack-dir))]
        (is (:success? result))
        (is (= "dir-pack" (get-in result [:pack :pack/id]))))))

  (testing "Loads rules from rules/ subdirectory"
    (let [pack-dir (io/file *test-dir* "pack-with-rules-dir")
          rules-dir (io/file pack-dir "rules")]
      (.mkdirs rules-dir)

      ;; Write manifest
      (spit (io/file pack-dir "pack.edn")
            "{:pack/id \"rules-dir-pack\"
              :pack/name \"Pack with Rules Dir\"
              :pack/version \"2026.01.22\"
              :pack/description \"Test\"
              :pack/author \"test\"
              :pack/categories []
              :pack/rules []
              :pack/created-at #inst \"2026-01-22T00:00:00Z\"
              :pack/updated-at #inst \"2026-01-22T00:00:00Z\"}")

      ;; Write rule file
      (spit (io/file rules-dir "test-rule.edn")
            "{:rule/id :test-rule
              :rule/title \"Test Rule\"
              :rule/description \"Loaded from directory\"
              :rule/severity :minor
              :rule/category \"800\"
              :rule/applies-to {}
              :rule/detection {:type :content-scan :pattern \"test\"}
              :rule/enforcement {:action :warn :message \"Found test\"}}")

      (let [result (loader/load-pack-from-directory (.getPath pack-dir))]
        (is (:success? result))
        (is (= 1 (count (get-in result [:pack :pack/rules]))))
        (is (= :test-rule (get-in result [:pack :pack/rules 0 :rule/id]))))))

  (testing "Directory rules override inline rules"
    (let [pack-dir (io/file *test-dir* "override-pack")
          rules-dir (io/file pack-dir "rules")]
      (.mkdirs rules-dir)

      ;; Manifest with inline rule
      (spit (io/file pack-dir "pack.edn")
            "{:pack/id \"override-pack\"
              :pack/name \"Override Pack\"
              :pack/version \"2026.01.22\"
              :pack/description \"Test\"
              :pack/author \"test\"
              :pack/categories []
              :pack/rules [{:rule/id :same-id
                           :rule/title \"Inline Version\"
                           :rule/description \"From inline\"
                           :rule/severity :minor
                           :rule/category \"800\"
                           :rule/applies-to {}
                           :rule/detection {:type :content-scan :pattern \"inline\"}
                           :rule/enforcement {:action :warn :message \"Inline\"}}]
              :pack/created-at #inst \"2026-01-22T00:00:00Z\"
              :pack/updated-at #inst \"2026-01-22T00:00:00Z\"}")

      ;; Directory rule with same ID
      (spit (io/file rules-dir "same-id.edn")
            "{:rule/id :same-id
              :rule/title \"Directory Version\"
              :rule/description \"From directory\"
              :rule/severity :major
              :rule/category \"800\"
              :rule/applies-to {}
              :rule/detection {:type :content-scan :pattern \"directory\"}
              :rule/enforcement {:action :hard-halt :message \"Directory\"}}")

      (let [result (loader/load-pack-from-directory (.getPath pack-dir))
            rules (get-in result [:pack :pack/rules])
            rule (first rules)]
        (is (:success? result))
        (is (= 1 (count rules)))
        ;; Directory version should win
        (is (= "Directory Version" (:rule/title rule)))
        (is (= :major (:rule/severity rule)))))))

;------------------------------------------------------------------------------ Layer 3
;; Auto-detect and discovery tests

(deftest load-pack-auto-detect-test
  (testing "Auto-detects file format"
    (let [pack-file (io/file *test-dir* "auto.pack.edn")]
      (spit pack-file "{:pack/id \"auto-pack\"
                        :pack/name \"Auto Pack\"
                        :pack/version \"2026.01.22\"
                        :pack/description \"Auto detected\"
                        :pack/author \"test\"
                        :pack/categories []
                        :pack/rules []
                        :pack/created-at #inst \"2026-01-22T00:00:00Z\"
                        :pack/updated-at #inst \"2026-01-22T00:00:00Z\"}")

      (let [result (loader/load-pack (.getPath pack-file))]
        (is (:success? result))
        (is (= "auto-pack" (get-in result [:pack :pack/id]))))))

  (testing "Auto-detects directory format"
    (let [pack-dir (io/file *test-dir* "auto-dir")]
      (.mkdirs pack-dir)
      (spit (io/file pack-dir "pack.edn")
            "{:pack/id \"auto-dir-pack\"
              :pack/name \"Auto Dir Pack\"
              :pack/version \"2026.01.22\"
              :pack/description \"Auto detected directory\"
              :pack/author \"test\"
              :pack/categories []
              :pack/rules []
              :pack/created-at #inst \"2026-01-22T00:00:00Z\"
              :pack/updated-at #inst \"2026-01-22T00:00:00Z\"}")

      (let [result (loader/load-pack (.getPath pack-dir))]
        (is (:success? result))
        (is (= "auto-dir-pack" (get-in result [:pack :pack/id])))))))

(deftest discover-packs-test
  (testing "Discovers packs in directory"
    (let [packs-dir (io/file *test-dir* "packs")]
      (.mkdirs packs-dir)

      ;; Create a .pack.edn file
      (spit (io/file packs-dir "file-pack.pack.edn")
            "{:pack/id \"file\" :pack/name \"File\" :pack/version \"1\"
              :pack/description \"\" :pack/author \"\" :pack/categories []
              :pack/rules [] :pack/created-at #inst \"2026-01-01\" :pack/updated-at #inst \"2026-01-01\"}")

      ;; Create a pack directory
      (let [dir-pack (io/file packs-dir "dir-pack")]
        (.mkdirs dir-pack)
        (spit (io/file dir-pack "pack.edn")
              "{:pack/id \"dir\" :pack/name \"Dir\" :pack/version \"1\"
                :pack/description \"\" :pack/author \"\" :pack/categories []
                :pack/rules [] :pack/created-at #inst \"2026-01-01\" :pack/updated-at #inst \"2026-01-01\"}"))

      (let [discovered (loader/discover-packs (.getPath packs-dir))]
        (is (= 2 (count discovered)))
        (is (some #(= :file (:type %)) discovered))
        (is (some #(= :directory (:type %)) discovered))))))

(deftest load-all-packs-test
  (testing "Loads all packs from directory"
    (let [packs-dir (io/file *test-dir* "all-packs")]
      (.mkdirs packs-dir)

      ;; Create two packs
      (spit (io/file packs-dir "pack1.pack.edn")
            "{:pack/id \"pack1\" :pack/name \"Pack 1\" :pack/version \"2026.01.01\"
              :pack/description \"First\" :pack/author \"test\" :pack/categories []
              :pack/rules [] :pack/created-at #inst \"2026-01-01\" :pack/updated-at #inst \"2026-01-01\"}")

      (spit (io/file packs-dir "pack2.pack.edn")
            "{:pack/id \"pack2\" :pack/name \"Pack 2\" :pack/version \"2026.01.02\"
              :pack/description \"Second\" :pack/author \"test\" :pack/categories []
              :pack/rules [] :pack/created-at #inst \"2026-01-01\" :pack/updated-at #inst \"2026-01-01\"}")

      (let [result (loader/load-all-packs (.getPath packs-dir))]
        (is (= 2 (count (:loaded result))))
        (is (empty? (:failed result)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Run tests
  (clojure.test/run-tests 'ai.miniforge.policy-pack.loader-test)

  :leave-this-here)
