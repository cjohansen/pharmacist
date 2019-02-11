(ns pharmacist.cljs-test-helper
  (:require [pharmacist.test-helper :refer [test-name]]))

(defmacro defscenario-async [scenario body]
  `(cljs.test/deftest ~(test-name scenario)
     (cljs.test/async ~'done
       (cljs.core.async/take!
        (binding [*print-namespace-maps* false]
          (pharmacist.test-helper/test-within 500 ~body))
        (fn [~'_] (~'done))))))

(defmacro defscenario [scenario body]
  `(cljs.test/deftest ~(test-name scenario)
     (binding [*print-namespace-maps* false]
       ~body)))
