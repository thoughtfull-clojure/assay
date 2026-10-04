(ns systems.thoughtfull.assay.git.interface-test
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.test :refer [deftest is]]
   [systems.thoughtfull.assay.git.interface :as git]))

(defn- temp-dir
  []
  (doto (java.io.File/createTempFile "assay" "")
    (.delete)
    (.mkdirs)))

(defn- sh!
  [dir & args]
  (let [{:keys [exit err out]} (apply sh/sh (concat args [:dir dir]))]
    (assert (zero? exit) err)
    out))

(defn- commit!
  [root message]
  (sh! root "git" "add" "-A")
  (sh! root "git" "-c" "user.name=t" "-c" "user.email=t@t" "-c"
    "commit.gpgsign=false" "commit" "-q" "--no-verify" "-m" message))

(deftest git-test
  (let [root (temp-dir)]
    (sh! root "git" "init" "-q" "-b" "main")
    (spit (io/file root "a.clj") "(ns a)")
    (spit (io/file root "b.clj") "(ns b)")
    (commit! root "base")
    (sh! root "git" "checkout" "-q" "-b" "feature")
    (spit (io/file root "a.clj") "(ns a) (def x 1)")
    (commit! root "change a")
    (spit (io/file root "b.clj") "(ns b) (def y 1)")
    (spit (io/file root "c.clj") "(ns c)")
    (let [base (git/merge-base root "main")
          out (temp-dir)]
      (is (= (sh! root "git" "rev-parse" "main") (str base "\n")))
      (is (= #{"a.clj" "b.clj" "c.clj"} (git/changed-files root base)))
      (git/extract root base out)
      (is (= "(ns a)" (slurp (io/file out "a.clj"))))
      (is (not (.exists (io/file out "c.clj")))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"git merge-base"
          (git/merge-base root "no-such-ref")))))

(deftest subdirectory-test
  (let [repo (temp-dir)
        root (io/file repo "ws")
        out (temp-dir)]
    (.mkdirs root)
    (sh! repo "git" "init" "-q" "-b" "main")
    (spit (io/file repo "outside.clj") "(ns outside)")
    (spit (io/file root "inside.clj") "(ns inside)")
    (commit! repo "base")
    (spit (io/file repo "outside.clj") "(ns outside) (def x 1)")
    (spit (io/file root "inside.clj") "(ns inside) (def x 1)")
    (let [base (git/merge-base root "main")]
      (is (= #{"inside.clj"} (git/changed-files root base)))
      (git/extract root base out)
      (is (= "(ns inside)" (slurp (io/file out "inside.clj"))))
      (is (not (.exists (io/file out "outside.clj")))))))
