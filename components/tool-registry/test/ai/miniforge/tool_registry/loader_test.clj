(ns ai.miniforge.tool-registry.loader-test
  "Tests for tool-registry loader functionality."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.java.io :as io]
   [ai.miniforge.tool-registry.loader :as loader]
   [ai.miniforge.tool-registry.registry :as registry]))

;------------------------------------------------------------------------------ Test fixtures

(def ^:dynamic *registry* nil)
(def ^:dynamic *temp-dir* nil)

(defn temp-dir-fixture [f]
  (let [temp (io/file (System/getProperty "java.io.tmpdir")
                      (str "tool-registry-test-" (System/currentTimeMillis)))]
    (.mkdirs temp)
    (binding [*temp-dir* temp]
      (try
        (f)
        (finally
          ;; Clean up temp files
          (doseq [file (reverse (file-seq temp))]
            (.delete file)))))))

(defn registry-fixture [f]
  (binding [*registry* (registry/create-registry {:project-dir (str *temp-dir*)})]
    (f)))

(use-fixtures :each temp-dir-fixture registry-fixture)

;------------------------------------------------------------------------------ Helper functions

(defn- write-tool-file [dir filename content]
  (let [parent (io/file dir)]
    (.mkdirs parent)
    (spit (io/file parent filename) (pr-str content))))

;------------------------------------------------------------------------------ Single file loading tests

(deftest load-tool-from-file-test
  (testing "loads valid tool from EDN file"
    (let [tool {:tool/id :test/sample
                :tool/type :function
                :tool/name "Sample Tool"}
          file (io/file *temp-dir* "sample.edn")]
      (spit file (pr-str tool))
      (let [{:keys [success? tool errors]} (loader/load-tool-from-file (.getPath file))]
        (is success?)
        (is (= :test/sample (:tool/id tool)))
        (is (nil? errors)))))

  (testing "returns error for non-existent file"
    (let [{:keys [success? errors]} (loader/load-tool-from-file "/nonexistent/file.edn")]
      (is (not success?))
      (is (some? errors))))

  (testing "returns error for invalid EDN"
    (let [file (io/file *temp-dir* "invalid.edn")]
      (spit file "{{{{invalid edn")
      (let [{:keys [success? errors]} (loader/load-tool-from-file (.getPath file))]
        (is (not success?))
        (is (some? errors)))))

  (testing "infers type from parent directory"
    (let [lsp-dir (io/file *temp-dir* "lsp")
          tool {:tool/id :test/inferred
                :tool/name "Inferred Type"}]
      (.mkdirs lsp-dir)
      (spit (io/file lsp-dir "test.edn") (pr-str tool))
      (let [{:keys [success? tool]} (loader/load-tool-from-file
                                     (.getPath (io/file lsp-dir "test.edn")))]
        (is success?)
        (is (= :lsp (:tool/type tool)))))))

;------------------------------------------------------------------------------ Discovery tests

(deftest discover-tools-test
  (testing "discovers tools in project directory"
    ;; Create project tools directory
    (let [tools-dir (io/file *temp-dir* ".miniforge" "tools" "lsp")]
      (.mkdirs tools-dir)
      (write-tool-file tools-dir "test.edn"
                       {:tool/id :lsp/test
                        :tool/type :lsp
                        :tool/name "Test"})
      (let [discovered (loader/discover-tools *registry*)]
        ;; Should find at least the project tool
        (is (some #(= :project (:source %)) discovered))))))

;------------------------------------------------------------------------------ Bulk loading tests

(deftest load-all-tools-test
  (testing "loads tools from project directory"
    (let [tools-dir (io/file *temp-dir* ".miniforge" "tools" "lsp")]
      (.mkdirs tools-dir)
      (write-tool-file tools-dir "test1.edn"
                       {:tool/id :lsp/test1
                        :tool/type :lsp
                        :tool/name "Test 1"
                        :tool/config {:lsp/command ["test1"]}})
      (write-tool-file tools-dir "test2.edn"
                       {:tool/id :lsp/test2
                        :tool/type :lsp
                        :tool/name "Test 2"
                        :tool/config {:lsp/command ["test2"]}})
      (let [{:keys [loaded failed]} (loader/load-all-tools *registry*)]
        ;; Should load the two project tools (may also include built-in)
        (is (>= (count loaded) 2))
        (is (contains? (set loaded) :lsp/test1))
        (is (contains? (set loaded) :lsp/test2)))))

  (testing "reports failed files"
    (let [tools-dir (io/file *temp-dir* ".miniforge" "tools" "lsp")]
      (.mkdirs tools-dir)
      ;; Create an invalid tool file
      (spit (io/file tools-dir "invalid.edn") "{{invalid")
      (let [{:keys [failed]} (loader/load-all-tools *registry*)]
        (is (some #(re-find #"invalid\.edn" (:path %)) failed))))))

(deftest reload-all-tools-test
  (testing "clears and reloads registry"
    (let [tools-dir (io/file *temp-dir* ".miniforge" "tools" "lsp")]
      (.mkdirs tools-dir)
      (write-tool-file tools-dir "tool.edn"
                       {:tool/id :lsp/reloadable
                        :tool/type :lsp
                        :tool/name "Reloadable"
                        :tool/config {:lsp/command ["test"]}})

      ;; Register a tool manually
      (registry/register-tool *registry*
                              {:tool/id :manual/tool
                               :tool/type :function
                               :tool/name "Manual"})

      ;; Verify manual tool exists
      (is (some? (registry/get-tool *registry* :manual/tool)))

      ;; Reload
      (loader/reload-all-tools *registry*)

      ;; Manual tool should be gone (was not in files)
      (is (nil? (registry/get-tool *registry* :manual/tool)))

      ;; File-based tool should be present
      (is (some? (registry/get-tool *registry* :lsp/reloadable))))))
