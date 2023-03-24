# ARCADIA Style Guide

This style guide specifically addresses programming ARCADIA. For suggestions on general Clojure programming style, consult [The Clojure Style Guide](https://guide.clojure.style/). For information on formatting namespace definitions, see [How to ns](https://stuartsierra.com/2016/clojure-how-to-ns.html).

## Table of Contents
1. [Components](Components)
1. [Models](Models)
1. [Environments](Environments)
1. [Debugging](Debugging)
## Components

### Namespaces
**Documentation:** Name space definitions include a docstring that describes the component's behavior and potentially its inspiration. 

- A component docstring appears immediately after the namespace's name and begins with a short, plain-language introduction to what the component does. This may include references to papers or other materials.  

  ```Clojure 
  "This component instantiates a task hierarchy and manages the currently active task, updating it when requested. Task switching takes two cycles. First, a focal switch-task activates this component. Second, this component outputs a representation of the task that is used to update other components and an action request to adopt an attentional strategy."
  ```

- The *Focus Responsive* section indicates the kind of elements, if any, the component looks for in the focus of attention. Minimally, these elements should be listed by name, and if there are more specific characteristics, these can be described. Additionally, the behavior of the component when one of these elements is in focus should be briefly described.

  ```Clojure 
  "Focus Responsive
    * update-task-hierarchy, changes the current hierarchy to a new one
    * switch-task, changes to a new task instance within the
      current hierarchy"
  ```

- The *Default Behavior* section specifies the activity of the component when not responding to the focus. 

  ```Clojure 
  "Default Behavior
    outputs the current task instance in the world 'task-wm'"
  ```

- The *Produces* section is a list of elements that the component delivers, with details about their intended purpose. The arguments of the elements with a brief description may also be included. 
  
  ```Clojure 
  "Produces
    task - the working memory version of the task, which includes
      :instance-name, the name of the task instance
      :handle, the name of the task schema
      :initial-responses, rules to fire when the task is 
        first activated
      :stimulus-responses, regular sr-links
      :strategy, attentional strategy
      :completion-condition, a descriptor that when matched
        indicates that the task is done
    (and so on)"
  ```
- An optional *Display* section describes any visual output produced by the component.

**Imported and required libraries:** Minimally, all components namespaces must include the following statement, which makes the shared protocol available.
```Clojure
(:require [arcadia.component.core :refer [Component]]
```
For components that take parameters, add the merge-parameters symbol. A few components may include Display or Logger protocols.
```Clojure
(:require [arcadia.component.core :refer [Component merge-parameters]]
```

**Parameters:** Components may have two types of parameters, which are constant values used within the component, that differ by accessibility through the ARCADIA API. 

- Constants are defined within a component for use throughout its definition, are declared private, include a docstring, and are not used to store dynamic state information.

  ```Clojure
  (def ^:private cs101-pi "a constant" 3.14)
  ```

- Parameters are values that can be passed into a component during its initialization in a model.

  - In this example, the object locator is sent two parameters, :sensor and :use-hats?.
    ```Clojure
        (model/add object-locator 
                  {:sensor (get-sensor :stable-viewpoint)
                   :use-hats? false})
    ```
  - Parameters are given meta-data that declares them as parameters and may optionally be declared as required, meaning that values have to be provided at initialization. Each parameter has a docstring, including an indication of whether it is required, and a default value.
    ```Clojure
    (def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)

    (def ^:parameter use-hats? "use mexican hats" false)
    ```
  - Default values for parameters can be overridden in the component's start function using merge-parameters.
    ```Clojure
    (defn start [& {:as args}]
      (let [p (merge-parameters args)
            w (sensor/camera-width (:sensor p))
            h (sensor/camera-height (:sensor p))]
        (->ObjectLocator ...)))
    ```

### Definition

**Component protocol:** All computation is carried out in the receive-focus function. The deliver-results function only converts that information into a set and returns it.

- receive-focus
  - Access component state variables using keywords.
    ```Clojure
    ; Bad
    (reset! x (:arguments (:region focus)))

    ; Ugly
    (reset! (:x component) (:region (:arguments focus)))

    ; Good
    (reset! (:x component) (-> focus :arguments :region))
    ```
- deliver-results
  - Return a set of elements. Use the proper idiom for the data structure that stores the results.
    ```Clojure
      ; :buffer contains a seqeuence of elements
      (deliver-result [component]
        @buffer))
      
      ; :buffer contains a single element
      (deliver-result [component]
        (list @buffer)))
    ```
**Other required functions:**

- print-method
  - Specifies what will be printed when the component is passed to a print function. 
  - Print the name of the defrecord, not the namespace.
  - Include any helpful state information in the braces.
    ```Clojure
    ; for a component called task-hierarchy
    ; if the root of the hierarchy is named "root", 
    ; will print "TaskHierarchy{root}"
    (defmethod print-method TaskHierarchy [comp ^java.io.Writer w]
      (.write w (format "TaskHierarchy{%s}" (-> comp :hierarchy deref :top))))
    ```
- start
  - Initializes the component passing along parameters, if needed.
  - Instantiate component records with arrow syntax.
    ```Clojure
    ; Ugly
    (defn start []
      (TaskWM. (atom ())))

    ; Good
    (defn start []
      (->TaskWM (atom ())))
    ```
  - For components with parameters, use a let block and merge-parameters.
    ```Clojure
    ; for components with a few parameters, pass them along 
    ; individually. here the :hierarchy parameter provides the 
    ; initial task hierarchy for the component.
    (defn start [& {:as args}]
      (let [p (merge-parameters args)]
        (->TaskHierarchy (atom nil) (atom nil) (atom (:hierarchy p)))))
    ```
  - If components have several related parameters that need to be referred to past initialization, consider storing them as a field in the component, but this is not the typical use.

**Display protocol:** Components that implement this protocol display information to users.

**Logger protocol:** Components that implement this protocol create a log of information that may be used in later analysis.

**Auxiliary methods:** Declare any other methods in a component namespace to be private.


## Models

### Namespaces
- Include a docstring that describes the purpose of the model.
  ```Clojure
  "This is a model of cued task switching for 
    basic magnitude/parity tasks. It provides 
    an example of how to use subvocalization 
    to drive task switches in a relatively
    straightforward scenario."
  ```
- Always require arcadia.models.core, and use :refer for arcadia.architecture.registry/get-sensor.
  ```Clojure
  (:require [arcadia.models.core]
            [arcadia.architecture.registry :refer [get-sensor]]))
  ```

### Attentional programs
- in progress

### Task definitions
- in progress

### Model initialization
- in progress

## Environments

## Debugging

### i#