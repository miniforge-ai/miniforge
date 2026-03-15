(ns ai.miniforge.knowledge.file-backed-store-test
  "Tests for FileBackedStore: persistence, round-trip, query."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.knowledge.store :as store]
   [ai.miniforge.knowledge.zettel :as zettel]
   [clojure.java.io :as io]))

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture [f]
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/miniforge-kb-test-" (System/currentTimeMillis))]
    (binding [*test-dir* dir]
      (try
        (f)
        (finally
          ;; Cleanup
          (let [d (io/file dir)]
            (when (.exists d)
              (doseq [file (.listFiles d)]
                (.delete file))
              (.delete d))))))))

(use-fixtures :each temp-dir-fixture)

(deftest file-backed-store-round-trip
  (testing "Write → restart → read round-trip"
    (let [store1 (store/create-file-backed-store {:path *test-dir*})
          z (zettel/create-zettel "test-uid" "Test Title"
                                  "Test content body" :rule
                                  :dewey "210" :tags [:clojure :test])]
      ;; Write
      (store/put-zettel store1 z)
      (is (= 1 (count (store/list-zettels store1))))

      ;; "Restart" — create a new store pointing to same directory
      (let [store2 (store/create-file-backed-store {:path *test-dir*})]
        (is (= 1 (count (store/list-zettels store2))))
        (let [loaded (store/get-zettel-by-uid store2 "test-uid")]
          (is (some? loaded))
          (is (= "Test Title" (:zettel/title loaded)))
          (is (= "Test content body" (:zettel/content loaded)))
          (is (= :rule (:zettel/type loaded)))
          (is (= "210" (:zettel/dewey loaded))))))))

(deftest file-backed-store-delete
  (testing "Delete removes file and index entry"
    (let [store1 (store/create-file-backed-store {:path *test-dir*})
          z (zettel/create-zettel "del-test" "Delete Me"
                                  "Should be removed" :learning)]
      (store/put-zettel store1 z)
      (is (= 1 (count (store/list-zettels store1))))

      (store/delete-zettel store1 (:zettel/id z))
      (is (= 0 (count (store/list-zettels store1))))

      ;; Restart — should still be empty
      (let [store2 (store/create-file-backed-store {:path *test-dir*})]
        (is (= 0 (count (store/list-zettels store2))))))))

(deftest file-backed-store-query
  (testing "Query works on file-backed store"
    (let [s (store/create-file-backed-store {:path *test-dir*})
          z1 (zettel/create-zettel "rule-1" "Clojure Rule"
                                   "Use threading macros" :rule
                                   :dewey "210" :tags [:clojure])
          z2 (zettel/create-zettel "learning-1" "Test Pattern"
                                   "Always test edge cases" :learning
                                   :dewey "400" :tags [:testing])]
      (store/put-zettel s z1)
      (store/put-zettel s z2)

      (is (= 1 (count (store/query s {:tags [:clojure]}))))
      (is (= 1 (count (store/query s {:include-types [:learning]}))))
      (is (= 2 (count (store/query s {})))))))

(deftest file-backed-store-search
  (testing "Text search works on file-backed store"
    (let [s (store/create-file-backed-store {:path *test-dir*})
          z (zettel/create-zettel "search-test" "Threading Macros"
                                  "Use -> and ->> for readability" :rule
                                  :tags [:clojure])]
      (store/put-zettel s z)
      (is (= 1 (count (store/search s "threading"))))
      (is (= 0 (count (store/search s "nonexistent")))))))

(deftest format-for-prompt-test
  (testing "format-for-prompt renders markdown block"
    (let [z1 (zettel/create-zettel "r1" "Rule One" "Content one" :rule :dewey "210")
          z2 (zettel/create-zettel "l1" "Learning One" "Content two" :learning)
          result (store/format-for-prompt [z1 z2] :implementer)]
      (is (string? result))
      (is (.contains result "Rule One"))
      (is (.contains result "Learning One"))
      (is (.contains result "(learning)"))
      (is (.contains result "implementer"))))

  (testing "format-for-prompt returns nil for empty zettels"
    (is (nil? (store/format-for-prompt [] :planner)))
    (is (nil? (store/format-for-prompt nil :planner)))))
