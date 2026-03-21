(ns ai.miniforge.cli.main.commands.fleet-test
  "Unit tests for fleet CLI commands: config loading, saving, add/remove."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.edn :as edn]
   [babashka.fs :as fs]
   [ai.miniforge.cli.main.commands.fleet :as sut]))

;; ============================================================================
;; Temp directory fixture
;; ============================================================================

(def ^:dynamic *tmp-dir* nil)

(defn tmp-dir-fixture [f]
  (let [dir (str (fs/create-temp-dir {:prefix "fleet-test-"}))]
    (binding [*tmp-dir* dir]
      (try
        (f)
        (finally
          (fs/delete-tree dir))))))

(use-fixtures :each tmp-dir-fixture)

;; ============================================================================
;; Helpers
;; ============================================================================

(def default-config
  {:fleet {:repos []}
   :version "test"})

(defn tmp-path [& segments]
  (apply str *tmp-dir* "/" segments))

;; ============================================================================
;; load-config tests
;; ============================================================================

(deftest load-config-missing-file-returns-defaults-test
  (testing "Returns default config when file does not exist"
    (let [result (sut/load-config nil "/nonexistent/config.edn" default-config)]
      (is (= default-config result)))))

(deftest load-config-reads-existing-file-test
  (testing "Reads and merges config from existing file"
    (let [path (tmp-path "config.edn")
          file-config {:fleet {:repos ["org/repo-1"]}}]
      (spit path (pr-str file-config))
      (let [result (sut/load-config path nil default-config)]
        (is (= ["org/repo-1"] (get-in result [:fleet :repos])))))))

(deftest load-config-merges-with-defaults-test
  (testing "File config is merged with defaults (file wins on conflict)"
    (let [path (tmp-path "config.edn")
          file-config {:fleet {:repos ["r1"]} :extra :data}]
      (spit path (pr-str file-config))
      (let [result (sut/load-config path nil default-config)]
        (is (= ["r1"] (get-in result [:fleet :repos])))
        (is (= :data (:extra result)))
        (is (= "test" (:version result))
            "Default keys not in file are preserved")))))

(deftest load-config-explicit-path-overrides-default-path-test
  (testing "Explicit path takes precedence over default config path"
    (let [explicit (tmp-path "explicit.edn")
          default-path (tmp-path "default.edn")]
      (spit explicit (pr-str {:fleet {:repos ["explicit"]}}))
      (spit default-path (pr-str {:fleet {:repos ["default"]}}))
      (let [result (sut/load-config explicit default-path default-config)]
        (is (= ["explicit"] (get-in result [:fleet :repos])))))))

(deftest load-config-nil-path-uses-default-path-test
  (testing "nil explicit path falls back to default config path"
    (let [default-path (tmp-path "default.edn")]
      (spit default-path (pr-str {:fleet {:repos ["from-default"]}}))
      (let [result (sut/load-config nil default-path default-config)]
        (is (= ["from-default"] (get-in result [:fleet :repos])))))))

;; ============================================================================
;; save-config tests
;; ============================================================================

(deftest save-config-creates-parent-dirs-test
  (testing "Creates parent directories if they don't exist"
    (let [path (tmp-path "nested" "dir" "config.edn")]
      (sut/save-config {:fleet {:repos ["r1"]}} path nil)
      (is (fs/exists? path))
      (is (= {:fleet {:repos ["r1"]}} (edn/read-string (slurp path)))))))

(deftest save-config-uses-default-path-when-nil-test
  (testing "Uses default path when explicit path is nil"
    (let [default-path (tmp-path "default-save.edn")]
      (sut/save-config {:data true} nil default-path)
      (is (fs/exists? default-path))
      (is (= {:data true} (edn/read-string (slurp default-path)))))))

(deftest save-config-overwrites-existing-test
  (testing "Overwrites existing config file"
    (let [path (tmp-path "overwrite.edn")]
      (spit path (pr-str {:old :data}))
      (sut/save-config {:new :data} path nil)
      (is (= {:new :data} (edn/read-string (slurp path)))))))

;; ============================================================================
;; fleet-add-cmd tests
;; ============================================================================

(deftest fleet-add-cmd-adds-repo-test
  (testing "Adds a repo to the fleet config"
    (let [path (tmp-path "add-config.edn")]
      (spit path (pr-str {:fleet {:repos []}}))
      (sut/fleet-add-cmd {:repo "org/new-repo" :config path} nil default-config)
      (let [saved (edn/read-string (slurp path))]
        (is (= ["org/new-repo"] (get-in saved [:fleet :repos])))))))

(deftest fleet-add-cmd-appends-to-existing-repos-test
  (testing "Appends to existing repos without removing them"
    (let [path (tmp-path "append-config.edn")]
      (spit path (pr-str {:fleet {:repos ["org/existing"]}}))
      (sut/fleet-add-cmd {:repo "org/new" :config path} nil default-config)
      (let [saved (edn/read-string (slurp path))]
        (is (= ["org/existing" "org/new"] (get-in saved [:fleet :repos])))))))

(deftest fleet-add-cmd-no-repo-prints-error-test
  (testing "Prints error when no repo is provided"
    (let [output (with-out-str
                   (sut/fleet-add-cmd {:repo nil} nil default-config))]
      (is (re-find #"Usage" output)))))

;; ============================================================================
;; fleet-remove-cmd tests
;; ============================================================================

(deftest fleet-remove-cmd-removes-repo-test
  (testing "Removes a repo from the fleet config"
    (let [path (tmp-path "remove-config.edn")]
      (spit path (pr-str {:fleet {:repos ["org/a" "org/b" "org/c"]}}))
      (sut/fleet-remove-cmd {:repo "org/b" :config path} nil default-config)
      (let [saved (edn/read-string (slurp path))]
        (is (= ["org/a" "org/c"] (get-in saved [:fleet :repos])))))))

(deftest fleet-remove-cmd-noop-for-missing-repo-test
  (testing "Removing a repo not in config is a no-op"
    (let [path (tmp-path "noop-config.edn")]
      (spit path (pr-str {:fleet {:repos ["org/a"]}}))
      (sut/fleet-remove-cmd {:repo "org/not-there" :config path} nil default-config)
      (let [saved (edn/read-string (slurp path))]
        (is (= ["org/a"] (get-in saved [:fleet :repos])))))))

(deftest fleet-remove-cmd-no-repo-prints-error-test
  (testing "Prints error when no repo is provided"
    (let [output (with-out-str
                   (sut/fleet-remove-cmd {:repo nil} nil default-config))]
      (is (re-find #"Usage" output)))))

;; ============================================================================
;; fleet-status-cmd tests
;; ============================================================================

(deftest fleet-status-cmd-shows-repo-count-test
  (testing "Status output includes repository count"
    (let [path (tmp-path "status-config.edn")]
      (spit path (pr-str {:fleet {:repos ["org/a" "org/b"]}}))
      (let [output (with-out-str
                     (sut/fleet-status-cmd {:config path} nil default-config))]
        (is (re-find #"Repositories.*2" output))))))

(deftest fleet-status-cmd-shows-default-state-when-no-state-file-test
  (testing "Shows zero counts when state file does not exist"
    (let [path (tmp-path "status-config2.edn")]
      (spit path (pr-str {:fleet {:repos []}}))
      (let [output (with-out-str
                     (sut/fleet-status-cmd {:config path} nil default-config))]
        (is (re-find #"Active Workflows.*0" output))
        (is (re-find #"Pending Workflows.*0" output))
        (is (re-find #"Completed.*0" output))
        (is (re-find #"Failed.*0" output))))))
