(ns systems.thoughtfull.assay.parse.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rewrite-clj.node :as n]
   [systems.thoughtfull.assay.parse.interface :as parse]))

(deftest top-level-forms-test
  (testing "ignores comments, whitespace, and uneval"
    (is (= ["(ns foo)" "(defn f [] 1)"]
          (map n/string
            (parse/top-level-forms
              (parse/parse-string
                "; comment\n(ns foo)\n#_(ignored)\n(defn f [] 1)\n")))))))

(deftest head-symbol-test
  (let [head (comp parse/head-symbol first parse/top-level-forms
               parse/parse-string)]
    (is (= "defn" (head "(defn f [] 1)")))
    (is (= "if" (head "(clojure.core/if x y z)")))
    (is (nil? (head "[a b]")))
    (is (nil? (head "(:k m)")))))

(deftest position-test
  (is (= {:row 3 :col 1}
        (-> (parse/parse-string "\n\n(defn f [] 1)")
          parse/top-level-forms
          first
          meta
          (select-keys [:row :col])))))
