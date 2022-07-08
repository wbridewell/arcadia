Batch Mode
===
This page covers tools that support running ARCADIA in batch mode, for example, running over several images or videos and recording the results. The page is split into five sections:
1. the environment, which must be appropriately constructed to support saving data;
1. the `arcadia.core/startup` function, which supports displaying results and saving them to a datafile;
1. the `arcadia.experiments/startup-sequence` function, which supports conducting a sequence of model runs over a sequence of files or environment configurations;
1. the `arcadia.experiments/startup-experiment` function, which supports iterating over permutations of parameter values in large-scale experiments; and
1. a reference describing all parameter names and values.

## The Environment
Typically, if the user wants to save data to a file, they'll need an environment that supports recording environmental actions taken by the model--these actions represent responses to a stimulus, such as pressing a virtual button or vocalizing. Note, however, that an alternative is to save data from accessible content on every cycle of operation, not only when the model performs environmental actions. See the next section for details on how to save data on every cycle.

The ARCADIA master branch includes the stimulus-player environment, which supports recording environmental actions in response to still images or videos (it can take the place of the static-image or video-player environments). In this section, we describe the stimulus-player, and then provide some details on how the user can develop their own, alternative environment that supports recording environmental actions or other data.

The stimulus-player can be configured with parameters such as the following.

```Clojure
{:file-path (.getPath (clojure.java.io/resource
                    "test-files/MOT_Extrap_2Targets.mp4"))
 :max-actions 2
 :max-cycles 250
 :minimum-cycles-before-action 10}
 ```

Given this configuration, the environment will play the specified stimulus video until one of three conditions is met: a) the video ends, b) the model performs two environmental actions, or c) the model runs for 250 cycles. Any environmental actions during the first 10 cycles will be ignored. Note that, by default, `:max-actions` is 1, and `:max-cycles` and `:minimum-cycles-before-action` are nil, meaning those parameters should be ignored.

The stimulus-player supports both saving data and displaying the results whenever it finishes playing a stimulus. To support these things in a new environment, the designer should ensure that the environment returns the appropriate information whenever `arcadia.simulator.environment.core/step` is called. Notably, the environment should return a hash-map that can include the following keys:

`:done?` A Boolean indicating whether the environment has completed.

`:data` A hash-map containing data about how the model responded to the environment. For example, with the stimulus-player, if `:max-actions` is 2 then the :data hash-map will include the keys `:response-1` and `:response-2`. The `:data` should be populated when there is new data available and nil on other cycles.

`:message` A string that can be used to display information about what is happening in the environment. For example, the stimulus-player provides a string of all environmental actions taken on the current stimulus. This should be populated when there is an update to be displayed and nil on other cycles (the display will continue showing the previous message until a new one is available).

`:increment-message-count?` On cycles where a `:message` is provided, if this value is true, then a number will be displayed along with the number, which will be one greater than the number displayed by the previous message.


## arcadia.core/startup

ARCADIA's core startup function can support saving and displaying results while running a model. Firstly, to display results, the user need only include the `display.environment-messages` component in their model file. This component, when it is present, will automatically be updated with any message provided by the environment (see the previous section).

To save data to the "arcadia/data" directory, the user should use two optional keyword arguments to the `startup` function.
* `:save-data?` Indicates whether data should be saved.
* `:data-parameters` A hash-map of parameters for saving data.

The full set of parameters for saving data can be found in the Reference section of this document. Here is an example.

```Clojure
:save-data? true
:data-parameters
{:output-file "MyResults"
 :every-cycle? false
 :output
   [[:description "My model"]
    [:vstm-size (data/o# (-> % :content
                         (d/filter-elements :name "object" :world "vstm")
                          count))]
    [:consistent (data/o# (= (:response-1 %) (:response-2 %)))]}
```

Here, the user has provided three data-parameters. `:output-file` indicates the name of the file where data will be saved (by default, a unique filename will be created from the current data and time). `:every-cycle?` is false, which means data will be saved only when the environment provides new data. This is the default behavior. The alternative is to save data on every cycle of operation and ignore data provided by the environment.

Finally, `:output` is a list of additional columns of information that will be included in the datafile. This information may be constant (for example, the string "My model"), or it may be specified using the `arcadia.utility.data/o#` function. This function operates similarly to the `arcadia.utility.display/i#` function. It creates an anonymous function that operates over a hash-map that includes the following keys:
* `:content` The current accessible content.
* `:focus` The current focus.
* `:registry` The current registry.
* `:cycle` The current cycle number.
* `:previous-output-map` A hash-map describing the full input to this function from the previous cycle.
* `:filename` The current filename if we are iterating over files (see the next section).
* Any columns of data provided by the environment.
* Any columns defined earlier in the `:output` list.


## arcadia.experiments/startup-sequence

Gathering data is more productive when a model is run multiple times. Therefore, the `startup-sequence` function supports running a model on multiple environment configurations. This can mean either a) running a model on every stimulus file (static image or video) in a directory or b) running a model using each of a sequence of environment parameter configurations.

The `startup-sequence` function is designed to appear highly similar to `core/startup.` It takes the same four required arguments.
```Clojure
[model-ns environment-ns environment-parameters max-cycles]
```
In addition, it can take any of the optional keyword arguments that `core/startup` takes. For example, `:record?` can be used to record video, and `:save-data?` can be used to save data. In each case, a single file will be used to record all the video or data from the entire sequence of model runs. Beyond this, it can take several additional keywords. For example:
```Clojure
{arcadia.experiments/startup-sequence
 'arcadia.models.mot-extrap
 'arcadia.simulator.environment.stimulus-player
 {:max-actions 2} ;;environment params here
 nil ;;Nil means no max cycle number
 :suppress-text? true
 :directory-path (.getPath (clojure.java.io/resource "Mot/Extrap"))
 :sort-files-by :start
 :shuffle? false
 :one-run-per-environment? true}
```

Here, `:suppress-text?` allows us to suppress printing to the repl during each model run, which can be helpful when there are many model runs occurring in sequence. `:directory-path` indicates a directory where (image or video) stimulus files can be found. `:sort-files-by` indicates the order in which these files will be processed, with `:start` meaning they should be sorted by the number found at the beginning of their filenames (`:end` is another option). `:shuffle?` determines whether the order of the environment configurations should be randomized. It is false by default.

`startup-sequence` operates by calling `core/startup` repeatedly over a sequence of environment configurations--in this case, one configuration for each file found in the directory. When doing so, there are two ways it can handle the model: a) it can create a new model instance for each environment configuration, or b) it can run a single model instance over the sequence of configurations, meaning for example that the cycle number will continue to increment across all the files in the directory without resetting. By default, `:one-run-per-environment?` is true, meaning there will be a separate model instance for each environment configuration.


## arcadia.experiments/startup-experiment

Finally, `startup-experiment` supports iterating over permutations of parameter values, conducting a sequence of model runs for each permutation, so that the user can determine how a model behaves given each permutation. The user can specify ranges of values for component parameters, environment parameters, dynamic variables, directories where stimuli will be found, and even model and environment namespaces, and a sequence of model runs will be conducted for every permutation of these values. The results will all be saved to a single datafile, which will include columns for each parameter range over which the experiment is iterating. Additionally, if `:record?` is true, the model runs will be saved to a sequence of movie files, one for each permutation of parameters (corresponding to one movie file for each call to `startup-sequence`).

The `startup-experiment` function is designed to be highly similar to `startup` and `startup-sequence`. It takes the same required arguments, except no max-cycles value is provided.

```Clojure
[model-ns environment-ns environment-parameters]
```

It can take all of the same optional keyword arguments as `startup-sequence`, along with several more. Consider the following example.

```Clojure
:sub-directory-range [1 2 3]
:comp-parameter-ranges
'([object-locator :noise-center (0.1 0.2 0.3)]
  [object-locator :noise-width (0.15 0.2)]
  [highlighter.crowding :crowding-dist (2.6 2.8 3.0)])
:dynamic-variable-ranges
'([arcadia.models.mot-extrap *mot-size-similarity-threshold* (1.5 2)])
:iterations 3
```

Here, `:sub-directory-range` provides a sequence of sub-directories within the `:directory-path` where stimulus files can be found (each sub-directory can be thought of as corresponding to a virtual participant in an experiment who views different stimuli than the other participants). `:comp-parameter-ranges` provides a sequence of ranges for different component parameters, for example a range of values for the object-locator component's `:noise-center` parameter. `:dynamic-variable-ranges` provides a sequence of ranges for dynamic variables, using the format [namespace variable-name range-of-values]. Finally, `:iterations` indicates that for each permutation of parameter values, the sequence of model runs should conducted 3 times--this can be useful when there's some element of randomness in the model runs. In this case, by "sequence of model runs" we mean running the model over all the stimuli found in a particular sub-directory.

For a detailed example of `startup-experiment,` see the function `arcadia.experiments/mot-extrap-experiment.`

## Reference

Here we list out names and descriptions for parameters used in each of the previous sections

### stimulus-player environment

| Parameter | Description |
|-|-|
| :file-path | Path of the (image or video) stimulus file to be processed. |
| :max-actions | Finish processing a file after this many environmental actions have been taken (if two actions occur on the same cycle, only one will be counted). |
| :max-cycles | Finish processing a file after this many cycles, even if the max actions haven't been reached. |
| :minimum-cycles-before-action | Ignore environmental actions that occur before this many cycles have passed. |
| :replay-final-video-frame? | If this is true, then don't finish processing when a video finishes--just continue to process the final video frame. Default value is false. |
| :buffer-frames | When the model run begins, provide a blank frame (a black rectangle) for this many cycles before beginning to provide the stimulus. Default value is 0 |

### Data parameters

These parameters can be specified by the `:data-parameters` keyword argument to `startup`, `startup-sequence`, or `startup-experiment`.

| Parameter | Description |
|-|-|
| :output-file | Name of the file to which data will be saved in the "arcadia/data" directory. If this is nil, a unique filename will be created from the date and time. |
| :every-cycle? | If this is true, then data will be saved on every cycle. If it is false, data will be saved only when the environment indicates there is data available. false by default. |
| :output | List of [key element] pairs where each key will become a column header in the output file and each element is either a constant or a function defined with `data/o#.` Output items are resolved in order, and later output items can refer to earlier ones in their `data/o#` function.|
| :initial-output | Similar to `:output`, but these items will appear earlier in the output file, to the left of any data provided by the environment. |

### experiments/startup-sequence

| Parameter | Description |
|-|-|
| :directory-path | Indicates that the model should be run on each file in the specified directory with the appropriate file extension. |
| :env-parameter-sequence | Provides a sequence of hash-maps, where each map describes values for environment parameters. Each hash-map in the sequence will be merged with the environment-parameters specified in the required argument to `startup-sequence` to get a full environment configuration, and the model will run on each configuration. Operates as an alternative to specifying a `:directory-path`. |
| :one-run-per-environment? | If this is true, then create a new model instance for each environment configuration. Otherwise, run a single model instance over all the environments. Default is true. |
| :suppress-text? | If this is true, then prevent anything from being printed to the repl during a model run. Default is false.|
| :file-extensions | Set of file extensions in the `:directory-path` to consider. Default includes common image and movie file extensions. |
| :sort-files-by | Specifies how files in the `:directory-path` should be sorted. Possible values are `:start` (sort by a number at the start of the filenames) and `:end`. |
| :skip-files | After sorting the files in `:directory-path`, skip this many files before beginning. |
| :shuffle? | If this is true, then randomize the environment configurations (whether they are specified via `:directory-path` or `:env-parameter-sequence`). It is possible to sort files, then skip some files, and then shuffle if desired. Default is false. |

### experiments/startup-experiment

| Parameter | Description |
|-|-|
| :model-range | A list of model namespaces to consider in the experiment. If this is non-nil, then the required argument model-namespace will be ignored. |
| :env-range | A list of environment namespaces to consider in the experiment. If this is non-nil, then the required argument env-namespace will be ignored. |
| :comp-parameter-ranges | A list of component parameter ranges to consider. Each item in the list should take the form ['component-name :parameter-name (list-of-parameter-values-to-try)] |
| :env-parameter-ranges | A list of environment parameter ranges to consider. Each item in the list should take the form [:parameter-name (list-of-parameter-values-to-try)] |
| :dynamic-variable-ranges | A list of dynamic variable ranges to consider. Each item in the list should take the form ['namespace 'dynamic-variable-name (list-of-values-to-try)] or ['dynamic-variable-name (list-of-values-to-try)]. In the latter case, the model's namespace is used. |
| :sub-directory-range | If `:directory-path` is specified, then this provides a list of subdirectories within that directory to consider. If this value is `:all`, then all subdirectories will be considered. |
| :iterations | Repeat every permutation of the various parameter ranges this many times. Default is 1. |
