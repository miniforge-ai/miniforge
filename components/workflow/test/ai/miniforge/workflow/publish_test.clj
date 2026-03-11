(ns ai.miniforge.workflow.publish-test
  (:require
   [clojure.test :refer [deftest is]]
   [ai.miniforge.workflow.publish :as publish]
   [babashka.fs :as fs]))

(deftest directory-publisher-test
  (let [temp-dir (fs/create-temp-dir {:prefix "workflow-publish-"})
        publisher (publish/create-directory-publisher temp-dir)
        result (publish/publish! publisher
                                 {:publication/path "reports/out.edn"
                                  :publication/content {:status :ok :count 3}})]
    (try
      (is (:success? result))
      (is (fs/exists? (:publication/path result)))
      (is (= {:status :ok :count 3}
             (read-string (slurp (:publication/path result)))))
      (finally
        (fs/delete-tree temp-dir)))))
