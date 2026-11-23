;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-container-test
  "OCI-CLI container copy and status with the runtime CLI stubbed."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.container :as oci-ctr]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.process :as oci-run]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} docker []
  (descriptor/make-descriptor {:runtime-kind :docker}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} copy-to-container-test
  (testing "the file goes in on stdin to `cat >` the quoted path, after a
            quoted `mkdir -p` of its parent; a failed copy is an error"
    (let [src  (java.io.File/createTempFile "miniforge-copy-" ".txt")
          seen (atom nil)]
      (.deleteOnExit src)
      (spit src "abc")
      (with-redefs [oci-run/run-runtime-process-timed
                    (fn [_ms _d args & {:keys [stdin-bytes]}]
                      (reset! seen [args (String. ^bytes stdin-bytes "UTF-8")])
                      {:exit 0})]
        (is (= {:copied-bytes 3}
               (:data (oci-ctr/copy-to-container (docker) "c1" (.getAbsolutePath src) "/w/a b/it's.txt"))))
        (is (= [["exec" "-i" "c1" "sh" "-c"
                 "mkdir -p '/w/a b' && cat > '/w/a b/it'\"'\"'s.txt'"] "abc"]
               @seen)))
      (with-redefs [oci-run/run-runtime-process-timed (fn [& _] {:exit 1 :err "denied"})]
        (is (= :copy-failed
               (:code (:error (oci-ctr/copy-to-container (docker) "c1" (.getAbsolutePath src) "/w/x")))))))))

(deftest ^{:stratum 1} copy-from-container-test
  (testing "the file comes out of `cat` of the quoted path into the local
            path, its parent directories made; a failed copy is an error"
    (let [dir  (.toFile (java.nio.file.Files/createTempDirectory
                         "miniforge-copy-" (make-array java.nio.file.attribute.FileAttribute 0)))
          dest (io/file dir "a" "b" "out.txt")
          seen (atom nil)]
      (with-redefs [oci-run/run-runtime-process-timed
                    (fn [_ms _d args & _]
                      (reset! seen args)
                      {:exit 0 :out-bytes (.getBytes "xyz" "UTF-8")})]
        (is (= {:copied-bytes 3}
               (:data (oci-ctr/copy-from-container (docker) "c1" "/w/it's.txt" (.getPath dest)))))
        (is (= ["exec" "c1" "sh" "-c" "cat '/w/it'\"'\"'s.txt'"] @seen))
        (is (= "xyz" (slurp dest))))
      (with-redefs [oci-run/run-runtime-process-timed (fn [& _] {:exit 1 :err "no such file"})]
        (is (= :copy-failed
               (:code (:error (oci-ctr/copy-from-container (docker) "c1" "/w/x" (.getPath dest)))))))
      (run! #(.delete ^java.io.File %) (reverse (file-seq dir))))))

(deftest ^{:stratum 1} inspect-container-status-test
  (testing "the runtime's State.Status maps to a status keyword; a failed
            inspect is an :unknown status with the error, not a failure"
    (doseq [[out status] [["running\n" :running] ["exited" :stopped] ["dead" :error]
                          ["paused" :unknown] ["created" :unknown]]]
      (with-redefs [oci-run/run-runtime (fn [_d & args]
                                          (is (= ["inspect" "c1" "--format" "{{.State.Status}}"] args))
                                          {:exit 0 :out out})]
        (is (= {:status status} (:data (oci-ctr/inspect-container (docker) "c1"))) out)))
    (with-redefs [oci-run/run-runtime (fn [& _] {:exit 1 :out "" :err "no such container"})]
      (is (= {:status :unknown :error "no such container"}
             (:data (oci-ctr/inspect-container (docker) "c1")))))))
