Model Overview
===

A model is a system implemented in ARCADIA that performs some task. Each model has, at minimum, a set of components that produce output on each cycle, and an attentional strategy for selecting one output element as the focus of attention. In this section, we will look at a very simple model file that demonstrates how a model's components and attentional strategy are coded. The example model, mot-simple, performs multiple object tracking. This model includes three functions that are required in every model file: `select-focus`, `sensor-setup`, and `component-setup`. It also includes an optional function, `example-run`, which can be called to see the model in action.

The code samples that follow are the entirety of the file `mot_simple.clj`, which can be found in the `models` directory.

```Clojure
(ns arcadia.models.mot-simple
  "Simple multiple object tracking model for instructional purposes."
  (:require [arcadia.models.core]
            [arcadia.architecture.registry :refer [get-sensor]]
            [arcadia.sensor.stable-viewpoint :as stable-viewpoint]
            [arcadia.utility.model :as model]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.general :as g]])
```
This is the file's header. Each model's namespace should be `arcadia.models.<model-name>`.

Models that do not use task hierarchies will employ a default attentional strategy found in `arcadia.architecture.focus-selector`.

```Clojure
(defn- select-focus-default
  "A general default-mode attentional program that is useful across models."
  [expected]
  (or (d/rand-element expected :type "action")
      (d/rand-element expected :name "object" :type "instance" :world nil)
      (d/rand-element expected :name "fixation")
      (g/rand-if-any (seq expected))))
```

The select-focus function provides the model's attentional strategy, which determines the focus of attention on each cycle. This function is called on the set of output elements produced by all components on the previous cycle. Here, the function indicates that the top priority elements have type `"action"`. If no such elements exist, then go down the list, first to those having the name `"object"`, type `"instance"`, and world `nil`, then to those whose name is `"fixation"`. Otherwise, select any random element. The macro `rand-element` is from the namespace `arcadia.utility.descriptors` and `rand-if-any` is from `arcadia.utility.general`.

```Clojure
(defn sensor-setup []
  (model/setup
   (model/add stable-viewpoint)))
```

The `sensor-setup` function provides instructions for registering any sensors that will be used in the model. Sensors provide input from the environment that can be used by components. Here, there is one sensor with the name `stable-viewpoint`. The `stable-viewpoint` sensor is useful for taking input from a static image or video. Unless a model requires other input, the model's `sensor-setup` function should be exactly the same as this one.

```Clojure
(defn component-setup []
  (model/setup
   (model/add image-segmenter {:sensor (get-sensor :stable-viewpoint)})
   (model/add highlighter.vstm)
   (model/add highlighter.color
                  {:sensor (get-sensor :stable-viewpoint)
                   :color-only? true})
   (model/add object-locator {:sensor (get-sensor :stable-viewpoint)})
   (model/add object-file-binder)
   (model/add vstm)
   (model/add display.objects)
   (model/add display.fixations
              {:sensor (get-sensor :stable-viewpoint)
               :display-name "Proto-Objects" :x 520 :y 20})))
```
The `component-setup` function provides instructions for registering any components that will be used by the model. Each component is added with the `model/add` macro, and the calls to this macro are wrapped in `model/setup` (both macros are defined in `arcadia.utility.model`). `model/add` takes one required argument, a component name; and one optional argument, a hash map of parameter values. The component name must be one of the names listed in `models/core.clj`.

Parameters provide information about how a component should operate. A component's parameters may come in two forms: required parameters and optional parameters. Required parameters must be assigned values, whereas optional parameters have default values that will be used if none is specified. To see which parameters are required for a component, and to view a component's default parameter values, check the top of its source file in the components directory.

In this example, many of the components have a required parameter `:sensor`, which specifies the sensor that is providing input from the environment. `(get-sensor :stable-viewpoint)` is a function defined in `arcadia.architecture.registry` that will return the sensor that has been registered with the name `stable-viewpoint`.

This example also includes optional parameter values for the `highlighter.color` and `display.fixations` components. For example, `:color-only?` is a parameter indicating whether  `highlighter.color` should only pick out segments that have a color (those that are not monchromatic). `:x` and `:y` are parameters indicating where on the screen `display.fixations` should show its results.

```Clojure
(defn example-run
  ([]
   (example-run 250))
  ([cycles]
   ;; use resolve to avoid circular dependencies for the example run
   ((resolve 'arcadia.core/startup)
    'arcadia.models.mot-simple
    'arcadia.simulator.environment.video-player
    {:video-path (.getPath (clojure.java.io/resource "test-files/MOT_Extrap_2Targets.mp4"))}
    cycles)))
```

The `example-run` function is enables users to see the model in action. Here, the function calls `arcadia.core/startup`, which is the usually way to run an ARCADIA model.  The `startup` function has four required arguments:

1. The model namespace (here it is this file's namespace).
1. The environment namespace (here it is `arcadia.simulator.environment.video-player`, which is an environment that supports taking a video as input).
1. A hash map containing arguments for the environment (here the hash map specifies the location of the video that will be used as input).
1. The number of cycles the model should run.
