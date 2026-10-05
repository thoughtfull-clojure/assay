(ns systems.thoughtfull.assay.workspace.interface-test
  (:require
   [clojure.test :refer [deftest is]]
   [systems.thoughtfull.assay.workspace.interface :as workspace]))

(defn- touch
  [root path]
  (doto (java.io.File. root path)
    (-> .getParentFile .mkdirs)
    (spit "")))

(deftest bricks-test
  (let [root (doto (java.io.File/createTempFile "assay" "")
               (.delete)
               (.mkdirs))]
    (touch root "components/zeta/src/z/core.clj")
    (touch root "components/alpha/src/a/interface.clj")
    (touch root "components/alpha/src/a/core.cljc")
    (touch root "components/alpha/src/a/notes.md")
    (touch root "components/alpha/test/a/core_test.clj")
    (touch root "bases/cli/src/cli/main.clj")
    (touch root "projects/app/deps.edn")
    (is (= [{:name "cli"
             :type :base
             :dir "bases/cli"
             :files ["bases/cli/src/cli/main.clj"]}
            {:name "alpha"
             :type :component
             :dir "components/alpha"
             :files ["components/alpha/src/a/core.cljc"
                     "components/alpha/src/a/interface.clj"]}
            {:name "zeta"
             :type :component
             :dir "components/zeta"
             :files ["components/zeta/src/z/core.clj"]}]
          (workspace/bricks root)))))

(deftest config-test
  (let [root (doto (java.io.File/createTempFile "assay" "")
               (.delete)
               (.mkdirs))]
    (is (= {:top-namespace nil :interface-ns "interface"}
          (workspace/config root)))
    (spit (java.io.File. root "workspace.edn")
      "{:top-namespace \"com.example\" :interface-ns \"ifc\"}")
    (is (= {:top-namespace "com.example" :interface-ns "ifc"}
          (workspace/config root)))))
