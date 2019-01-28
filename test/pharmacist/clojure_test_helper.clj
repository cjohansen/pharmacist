(ns pharmacist.clojure-test-helper
  (:require [pharmacist.test-helper :refer [test-name test-within]]))

(defmacro defscenario-async [scenario body]
  `(clojure.test/deftest ~(test-name scenario)
     (clojure.core.async/<!!
      (binding [*print-namespace-maps* false]
        (test-within 500 ~body)))))

(defmacro defscenario [scenario body]
  `(clojure.test/deftest ~(test-name scenario)
     (binding [*print-namespace-maps* false]
       ~body)))
