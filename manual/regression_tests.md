Regression Tests
===

## When should they be used?
Regression tests are helpful for ensuring that a base set of models work consistently with the latest code. This is desirable because it demonstrates ARCADIA's generality: we don't need to write slightly different code to get each model to work. Additionally, as developers we save time if we can reuse code across models. That said, it isn't necessary that every model use exactly the same code base; some new models are highly experimental, whereas some old models played a historical role but are no longer being kept up to date. Thus, regression tests should be used only for models in the main branch that are currently relevant for demonstrating ARCADIA's capabilities. Ideally, coders will run these tests any time sizable changes are made to the main branch, and only code that passes all tests will be committed.

The current set of regression tests can be run by calling
```Clojure
(arcadia.regression-tests/run-tests)
```

## Setting up a regression test
Three steps are required to set up a test.

1. Define a `regression-test` function in a model's namespace. This function should return a vector `[pass? error-string]`, where `pass?` is a Boolean indicating whether the test passed and `error-string` is a string providing information on how the test failed.

1. Register the model in `arcadia/regression_tests.clj` by adding a string naming the model and the model's namespace to the `active-tests` hash map.

1. If the regression test requires any external files as input (e.g., images or videos), add these to the `resources/test-files` directory, and make sure to push them to the main branch on git.


Here is an example of a `regression-test` function, taken from the `mot-extrap` model.

```Clojure
(defn regression-test []
  (let [results
        ((resolve 'arcadia.core/startup) ;;use resolve to avoid circular dependencies
         'arcadia.models.mot-extrap
         'arcadia.simulator.environment.video-player
         {:video-path (.getPath (clojure.java.io/resource "test-files/MOT_Extrap_2Targets.mp4"))
          :viewing-width 15.375
          :viewing-height 15.375}
         250 ;;number of cycles
         :output-map
         {:guesses (final-content filter-elements :name "new-object-guess")
          :prediction (first-focus element-matches? :name "fixation" :reason "maintenance" :expected-region some?)})]
 
    (cond
      (nil? (:prediction results))
      [false "Failed to predict a target's location during an occlusion event."]
 
      (< (count (:guesses results)) 2)
      [false "Failed to make two guesses about whether the highlighted objects are the tracked targets."]
 
      (= 1 (count (filter-elements (:guesses results) :old? true)))
      [false "Only recognized one of the two tracked targets."]
 
      (not-any-element (:guesses results) :old? true)
      [false "Recognized neither of the two tracked targets."]
 
      :else
      [true ""])))
```
The call to `startup` is similar to other calls used to run ARCADIA models (note that the call references a video file in `resources/test-files`). The critical addition is the use of the `:output-map` keyword argument. When this argument is used, `startup` will return an `output-map`, a hash map in which the specified keys are associated with information about the state of accessible content or the focus at different points in the model's run. Let us consider the first pair in the `output-map`.

```Clojure
:guesses (final-content filter-elements :name "new-object-guess")
```

Here, `final-content` is a macro indicating that after the final cycle of the model run, `(fn[c] (filter-elements c :name "new-object-guess"))` will be called on accessible content. The returned value will be associated with `:guesses` in the `output-map`. In total, there are six available macros.

| Macro | Description |
|-|-|
| first-content | Calls the function on accessible content after every cycle and returns the first non-nil, non-false, non-empty value (or else nil). |
| first-focus | Calls the function on the focus after every cycle and returns the first non-nil, non-false, non-empty value (or else nil). |
| content-number ## | Calls the function on accessible content after cycle number ##. |
| focus-number ## | Calls the function on the focus after cycle number ##. |
| final-content | Calls the function on accessible content after the final cycle of the model run. |
| final-focus | Calls the function on the focus after the final cycle of the model run. |