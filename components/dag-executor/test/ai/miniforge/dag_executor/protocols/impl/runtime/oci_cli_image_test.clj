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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-image-test
  "OCI-CLI task-runner image functions with the runtime CLI stubbed: digest
   reads, pulls, the Dockerfile lookup and build, and ensure."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli :as oci-cli]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.image :as oci-img]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.process :as oci-run]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private digest
  (str "sha256:" (apply str (repeat 64 "a"))))

(defn- ^{:stratum 0} docker []
  (descriptor/make-descriptor {:runtime-kind :docker}))

(defn- ^{:stratum 0} jar-with-resource
  "A temp JAR holding `path` with `content`, as a URL a class loader reads."
  [path content]
  (let [jar (java.io.File/createTempFile "miniforge-test-" ".jar")]
    (.deleteOnExit jar)
    (with-open [out (java.util.jar.JarOutputStream. (io/output-stream jar))]
      (.putNextEntry out (java.util.jar.JarEntry. ^String path))
      (.write out (.getBytes ^String content "UTF-8"))
      (.closeEntry out))
    (.toURL (.toURI jar))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} image-repo-digest-test
  (testing "the first RepoDigest, trimmed; nil when inspect fails, prints
            nothing, or throws"
    (with-redefs [oci-run/run-runtime (fn [_d & args]
                                        (is (= ["image" "inspect" "img" "--format" "{{index .RepoDigests 0}}"] args))
                                        {:exit 0 :out "repo@sha256:abc\n"})]
      (is (= "repo@sha256:abc" (oci-img/image-repo-digest (docker) "img"))))
    (with-redefs [oci-run/run-runtime (fn [& _] {:exit 0 :out "\n"})]
      (is (nil? (oci-img/image-repo-digest (docker) "img"))))
    (with-redefs [oci-run/run-runtime (fn [& _] {:exit 1 :out "" :err "no such image"})]
      (is (nil? (oci-img/image-repo-digest (docker) "img"))))
    (with-redefs [oci-run/run-runtime (fn [& _] (throw (ex-info "no runtime" {})))]
      (is (nil? (oci-img/image-repo-digest (docker) "img"))))))

(deftest ^{:stratum 1} pull-image-is-best-effort-test
  (testing "a pull that throws (no runtime) is nil, not an exception"
    (with-redefs [oci-run/run-runtime (fn [& _] (throw (ex-info "no runtime" {})))]
      (is (nil? (oci-img/pull-image! (docker) "img"))))))

(deftest ^{:stratum 1} resolve-image-digest-pulls-only-a-missing-image-test
  (testing "a local image is not pulled; a missing one is pulled before its
            config digest is read"
    (doseq [[present? pulls] [[true []] [false [["pull" "img"]]]]]
      (let [calls (atom [])]
        (with-redefs [oci-run/run-runtime
                      (fn [_d & args]
                        (swap! calls conj (vec args))
                        (cond
                          (= ["image" "inspect" "img"] args) {:exit (if present? 0 1) :out ""}
                          (= "pull" (first args))            {:exit 0 :out ""}
                          :else                              {:exit 0 :out (str digest "\n")}))]
          (is (= digest (oci-img/resolve-image-digest! (docker) "img")))
          (is (= pulls (filter #(= "pull" (first %)) @calls)) (str "present? " present?)))))))

(deftest ^{:stratum 1} find-dockerfile-path-gives-a-jar-dockerfile-its-own-directory-test
  (testing "a Dockerfile read from a JAR is extracted into a directory holding
            nothing else, since build-image! sends that directory as the build
            context; a filesystem path is returned as it is; a missing one is nil"
    (let [loader (java.net.URLClassLoader. (into-array java.net.URL [(jar-with-resource "dk/Dockerfile.t" "FROM scratch\n")])
                                           (.getContextClassLoader (Thread/currentThread)))
          thread (Thread/currentThread)
          before (.getContextClassLoader thread)
          path   (try
                   (.setContextClassLoader thread loader)
                   (oci-img/find-dockerfile-path "dk/Dockerfile.t")
                   (finally (.setContextClassLoader thread before)))
          dir    (.getParentFile (io/file path))]
      (is (= "FROM scratch\n" (slurp path)))
      (is (= ["Dockerfile"] (vec (.list dir))))
      (is (not= (.getCanonicalPath (io/file (System/getProperty "java.io.tmpdir")))
                (.getCanonicalPath dir)))))
  (let [f (java.io.File/createTempFile "Dockerfile" ".t")]
    (.deleteOnExit f)
    (is (= (.getAbsolutePath f) (oci-img/find-dockerfile-path (.getAbsolutePath f)))))
  (is (nil? (oci-img/find-dockerfile-path "no/such/Dockerfile"))))

(deftest ^{:stratum 1} build-image-builds-from-the-dockerfile-directory-test
  (testing "build-image! tags the image, names the Dockerfile and sends its
            directory as the context; a failed build and a missing Dockerfile
            are errors"
    (let [f    (java.io.File/createTempFile "Dockerfile" ".t")
          args (atom nil)]
      (.deleteOnExit f)
      (with-redefs [oci-run/run-runtime (fn [_d & a] (reset! args (vec a)) {:exit 0 :out ""})]
        (is (= {:image "img:1" :dockerfile (.getAbsolutePath f) :built? true}
               (:data (oci-img/build-image! (docker) "img:1" (.getAbsolutePath f)))))
        (is (= ["build" "-t" "img:1" "-f" (.getAbsolutePath f) (.getParent f)] @args)))
      (with-redefs [oci-run/run-runtime (fn [& _] {:exit 1 :out "" :err "bad"})]
        (is (= :build-failed (:code (:error (oci-img/build-image! (docker) "img:1" (.getAbsolutePath f))))))))
    (is (= :dockerfile-not-found
           (:code (:error (oci-img/build-image! (docker) "img:1" "no/such/Dockerfile")))))))

(deftest ^{:stratum 1} ensure-image-test
  (testing "an existing image is not rebuilt unless forced; an unknown key is an error"
    (let [[k {:keys [image]}] (first oci-img/task-runner-images)
          built (atom [])]
      (with-redefs [oci-img/image-exists? (fn [_ _] true)
                    oci-img/build-image!  (fn [_ img df] (swap! built conj [img df]) (result/ok {:built? true}))]
        (is (= {:image image :dockerfile (:dockerfile (get oci-img/task-runner-images k))
                :built? false :existed? true}
               (:data (oci-img/ensure-image! (docker) k))))
        (is (empty? @built))
        (is (:built? (:data (oci-img/ensure-image! (docker) k :force? true))))
        (is (= [image] (map first @built))))
      (is (= :unknown-image-key (:code (:error (oci-img/ensure-image! (docker) :no-such-key))))))))

(deftest ^{:stratum 1} ensure-all-images-covers-every-task-runner-image-test
  (testing "ensure-all-images! returns each task-runner image key's result,
            passing :force? through"
    (let [seen (atom [])]
      (with-redefs [oci-img/ensure-image! (fn [_ k & {:keys [force?]}] (swap! seen conj [k force?]) (result/ok {:image k}))]
        (is (= (set (keys oci-img/task-runner-images))
               (set (keys (oci-cli/ensure-all-images! (docker) :force? true)))))
        (is (every? (fn [[_ force?]] (true? force?)) @seen))))))
