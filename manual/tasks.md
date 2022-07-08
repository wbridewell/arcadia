Task Representations
===

The library `arcadia.utility.tasks` contains functions for creating and firing SR-links, creating tasks, and creating subvocalization requests. The function task constructs a new task schema from from four values: a task handle, an attentional strategy, and a sequence of SR-links, and a completion condition, which is a descriptor. Task schemata are instantiated using the information encoded in a task hierarchy.

To illustrate the parts of a task schemata, we will be using the example definition below, which creates a schema for the magnitude task in the Cued Task Switching model.

```Clojure
(defn- select-focus-task
  "Select a focus of attention that prefers actions in pursuit of a
  specific task."
  [kind expected]
  (or (d/rand-element expected :type "action" :task kind)
      (d/rand-element expected :type "action" :name "subvocalize")))

(defn- task-switch [expected]
  (d/rand-element expected :name "switch-task" :type "action"))

(defn mag-task []
  (let [mag-response [(t/sr-link [(d/descriptor :name "object-property" :property :magnitude :value :low)]
                                 (push-button :b-odd-low :magnitude))
                      (t/sr-link [(d/descriptor :name "object-property" :property :magnitude :value :high)]
                                 (push-button :b-even-high :magnitude))]
        mag-strategy [(att/partial-strategy task-switch 5)
                      (att/partial-strategy (partial select-focus-task :magnitude) 4)
                      (att/partial-strategy att/default-strategy 1 0.1)]]
    (t/task "magnitude"
            mag-strategy
            mag-response
            (d/descriptor :name "push-button" :type "environment-action"))))
```

## Handle
Every task schema has a handle, which is a string label used to refer to it when specifying the task hierarchy. 

In the example `"magnitude"` is the handle.

## Strategy
Tasks also contain their own attentional strategies which are unique to that task. Attentional strategies select an interlingua element from accessible content as the focus of attention to be broadcast out to components in the next cycle. While many ARCADIA models require only one attentional strategy to carry out their functions, the ability for tasks to impose their own attentional strategies enables flexible behavior. 

The strategy for the magnitude task contains three parts. The highest priority is task switching, which is indicated by an element named `"switch-task"`. The next highest priority are action requests that are specific to the task being carried out. Here, the example uses a parameterized function called `select-focus-task` to create this part of the strategy and specialize it to the magnitude task. As the final set of priorities, the task uses a default attentional strategy defined in `arcadia.utility.attention-strategy`. 

## SR-Links
Stimulus/Response links (SR-links) are the basis for modular conditional action in ARCADIA. Most tasks will have SR-links that are specific to that task. SR-links for tasks can be defined using the functions `sr-link` or `initial-response`. Initial responses should be used to parameterize components with elements having type `"automation"` or to request an action on task entry. These are executed once. The regular SR-links are evaluated on each cycle and should be used to issue action requests contingent on information produced on the previous cycle. 

In the magnitude task, there are two SR-links: one for each potential button press associated with task.

## Completion Condition
The last argument is a descriptor that picks out an element that signals task completion. Although this could be considered the goal of the task, it could refer to the output of a specific component that monitors for task completion and reports a "task finished" signal that the completion condition recognizes. In other words, it may be more expedient to construct a component that tracks the status of a particular goal than to attempt to capture the complexity of a goal state in a single descriptor.

The completion condition is used to construct the instantiated task hierarchy. For top-level tasks or tasks that only switch to other tasks through explicitly specified SR-links, you can use `(descriptor :name false)` as the condition because it will never be satisfied by an interlingua element.

## Cued Task-Switching Model
For the rest of this document, we will be referring to the full set of task definitions in the Cued Task Switching model (see `arcadia.models.cued-task-switching-mp`). This model performs a psychological experiment in the cued task-switching paradigm. First, a letter ("M" for magnitude, or "P" for parity) is displayed on the screen. This letter determines how the participant should respond to the next visual stimulus, a number; if "M" was shown and the number is less than 5, the participant should select the Low/Odd button, otherwise they should select the High/Even button. If, however, "P" was previously shown, the participant should respond to the number's parity: the participant should press the Low/Odd button for odd numbers, and the High/Odd button for even numbers.

```Clojure
(def cts-task-hierarchy
  {:top "cue"
   :nodes
   {"cue"        "cue"
    "magnitude"  "magnitude"
    "parity"     "parity"}
   :edges
   [{:parent "cue" :trigger (d/descriptor :name "subvocalize" :lexeme "magnitude") :child "magnitude"}
    {:parent "cue" :trigger (d/descriptor :name "subvocalize" :lexeme "parity") :child "parity"}]})

(def task-library (t/build-task-library (cue-task) (mag-task) (par-task)))
```

## Task Library
A task library consists of a collection of task schemata. The function `build-task-library` creates a task library from its arguments.

In the example, because the tasks are constructed by functions, the arguments to `build-task-library` are function calls. This is an arbitrary choice, and the tasks could be defined with `def` or specified inline. 

## Task Hierarchy
The task hierarchy encodes information on which task schemata to use and how they should be instantiated. The hierarchy has three parts. The `:nodes` are a map between instance names and schemata. The rest of the hierarchy refers to the instance names only. The `:top` argument specifies the task instance to load when ARCADIA first adopts the hierarchy. This is entry point. The `:edges` is a sequence of individual connections between a parent node and one of its children. The `:trigger` is a descriptor that detects when the condition for edge transition occurs. The edges should follow the constraints of a hierarchical structure in that they should only go from the top to the bottom of the network and each node should have only one parent but may have many children. If the same task schema is used in multiple locations in the hierarchy, it should be associated with a different instance name for each time that it appears as a `:child` in an edge.

Instantiating the hierarchy turns the edges into SR-links that switch from the parent to the child when the trigger condition is met. Additionally, a link back to the parent is created where the stimulus is the (child) task's completion condition. There is a unique characteristic of task hierarchies in ARCADIA. In addition to the direct links between parents and children, a task has links back to each of its ancestors. For instance, if you were executing task instance D in a task hierarchy with an ancestory of A→B→C→D, then the normal upward link would have D return to C if its completion conditions were met. In ARCADIA, D will also have a link to B that activates if C's completion condition is met and a link to A that activates when B's completion condition is met. This allows short circuiting of subtasks if any of the higher level goals are satisfied for other reasons (e.g., by another agent, by environmental dynamics).

In the example, there are three task instances that are give the same names as their schemata. The top node is the task for cue interpretation, which subvocalizes the name of a task (magnitude or parity) indicated by the letter that appears on the display. This subvocalization triggers the task switch to either the magnitude or parity subtask. Once one of those tasks presses a button they are completed and return to the parent, cue task.

## Task Switching
In previous implementations of tasks in ARCADIA, task switching was carried out through subvocalization or model-specific techniques. In this version, the process has been generalized and streamlined. Task switching can be effectively ignored, although the "switch-task" action request should be at the top of any attentional strategy to ensure that task switches take priority. This preference is not built into the architecture because that would limit modeling task completion errors due to other priorities. 

## Components
To use tasks, a model needs to include two components: `task-ltm` and `task-hierarchy`. The `task-ltm` component operates as a long term memory store for task definitions. Currently its contents are created in a model file and passed to the component by passing it the result of a `build-task-library` function call. Future plans are to create a source file for task schemata and have `task-ltm` use the contents of that file for initialization. The `task-hierarchy` component takes the task hierarchy as an argument and stores the instantiated version of the hierarchy. Whenever the component loads a new hierarchy, it creates an interlingua element to that requests the relevant task schemata. In response, `task-ltm` produces those schemata and `task-hierarchy` uses them to assemble the instantiated hierarchy. In addition to storing the hierarchy, `task-hierarchy` serves as a task working memory, producing an element on each cycle that reports the currently active task. Furthermore, when a `switch-task` element is the focus of attention, `task-hierarchy` updates the active task and ensures that the attentional strategy is switched in ARCADIA's focus selector. 