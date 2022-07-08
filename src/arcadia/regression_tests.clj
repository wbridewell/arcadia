(ns ^{:doc "This code supports running regression tests to confirm that models still work.
      Regression tests will be used primarily in the main branch, where we have a set of
      models that should work with the latest version of the code. To add a model to the
      regression tests, you must first define the function regression-test in its namespace.
      This function should return the vector [pass? error-string], where pass? is a Boolean
      indicating whether the test passed. Then, you can register the model here by including
      a string naming the model and the model's namespace in the active-tests hashmap below."}

  arcadia.regression-tests
  (:require [arcadia.utility.general :as g]))


(def ^:private active-tests
  {"Multiple Object Tracking (Extrapolation)"
   'arcadia.models.mot-extrap})


(defn run-tests
  "Runs regression tests for all models registered in active-tests."
  []
  (println "Running regression tests...")
  (doseq [model (keys active-tests)]
    (printf "%s..." model)
    (let [[pass? output-string]
          (g/suppress-text-output ((ns-resolve (get active-tests model) 'regression-test)))]
      (if pass?
        (println "PASS")
        (printf "FAIL: %s%n" output-string)))))