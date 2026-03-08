(ns ai.miniforge.tui-views.file-subscription-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.file-subscription :as file-sub]))

(defn temp-dir
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "miniforge-file-sub-test-" (System/nanoTime)))
    .mkdirs))

(defn cleanup!
  [dir]
  (doseq [f (.listFiles dir)]
    (.delete f))
  (.delete dir))

(defn write-event-file!
  [dir file-name events]
  (let [file (io/file dir file-name)]
    (with-open [w (io/writer file)]
      (doseq [event events]
        (.write w (pr-str event))
        (.write w "\n")))
    file))

(deftest track-file-hydrate-flag-test
  (testing "hydrate? false tracks the file from EOF without replaying history"
    (let [dir (temp-dir)
          file (write-event-file! dir "wf.edn"
                                  [{:event/type :workflow/started
                                    :workflow/id (random-uuid)
                                    :workflow/spec {:name "test"}}])
          tracked (atom {})
          dispatched (atom [])]
      (try
        (file-sub/track-file! tracked #(swap! dispatched conj %) file {:hydrate? false})
        (is (empty? @dispatched))
        (is (= (.length file)
               @(get-in @tracked [(.getAbsolutePath file) :position])))
        (finally
          (cleanup! dir)))))

  (testing "hydrate? true replays existing events immediately"
    (let [dir (temp-dir)
          file (write-event-file! dir "wf.edn"
                                  [{:event/type :workflow/started
                                    :workflow/id (random-uuid)
                                    :workflow/spec {:name "test"}}])
          tracked (atom {})
          dispatched (atom [])]
      (try
        (file-sub/track-file! tracked #(swap! dispatched conj %) file {:hydrate? true})
        (is (= 1 (count @dispatched)))
        (finally
          (cleanup! dir))))))
