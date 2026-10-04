(ns systems.thoughtfull.assay-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [systems.thoughtfull.assay :as assay]))

(deftest analyze-file-test
  (is (= {:top-level-form-count 2
          :form-count 13}
        (assay/analyze-file (io/resource "sample.clj")))))
