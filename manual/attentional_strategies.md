Attentional Strategies
======
The flow of control in ARCADIA is determined largely by the attentional strategy (also called an attentional program or attentional priorities). The active strategy is a function that specifies which interlingua element will be the focus of attention during the next cycle. As input, it takes the elements produced by components during the current cycle, which were stored in the expectation structure, and as output it returns one of those elements as the focus of attention. 

Although the structure of attentional strategies is unconstrained, they have generally been written in the form of priority lists. More recently, the code for strategies has made use of descriptors as a means to standardize their appearance and to simplify development. 

## Examples

Attentional strategies originally took the form of a cond statement. 

```Clojure
;; Historical reference [Deprecated format]
(defn select-focus [expected]
  (let [new-objects (filter #(and (= (:name %) "object")
                                  (= (:type %) "instance")
                                  (= (:world %) nil)) expected)]
    (cond
      (some #(and (= (:name %) "change") (= (:world %) nil)) expected)
      (rand-nth (filter #(and (= (:name %) "change") (= (:world %) nil)) expected))

      (some #(= (:type %) "action") expected)
      (rand-nth (filter #(= (:type %) "action") expected))

      (seq new-objects)
      (rand-nth new-objects)

      (some #{"fixation"} (map #(:name %) expected))
      (first (sorter (filter #(= (:name %) "fixation") expected)))

      (seq expected)
      (rand-nth (seq expected)))))
```

In this example, `change` events are preferred over `action` requests, which are preferred over newly constructed objects, which are preferred over visual `fixation` requests. 

The redundancies in the test and expression pairs led to the use of a `let` block when `filter` predicates became more involved. The descriptor functions, available in `arcadia.utility.descriptors.clj`, eliminate the redundancy and reduce the chance of difficult to find errors due to typos in either the test or the expression of a cond pairing.

```Clojure
;; Historical reference [Deprecated format]
(defn select-focus [expected]  
  (or (d/rand-element expected :name "change" :world nil)
      (d/rand-element expected :type "action")
      (d/rand-element expected :name "object" :type "instance" :world nil)
      (d/rand-element expected :name "fixation")
      (rand-nth (seq expected))))
```

These representations of strategies are monolithic, meaning that they cannot be edited or inspected by models or components. Furthermore, the strategies are static, so lower priority elements could be starved, which may reduce responsiveness to the environment. To address these concerns, the representation of attentional strategies was made modular so a strategy is a sequence of partial strategies that have priorities. Each of these partial strategies can potentially be replaced, removed, or reprioritized. To prevent starvation, each partial strategy can also have a "step" argument that increases its priority by a fixed amount on each cycle. When an element from that partial strategy becomes the focus of attention, its priority is reset to its original level. The partial strategies are organized into tiers which are descriptors to be matched from left to right that otherwise share the same priority. 

```Clojure
(def task-switch (att/strategy-tier (d/descriptor :name "switch-task" :type "action" :world nil)))

(def events-and-actions (att/strategy-tier 
                          (d/descriptor :name "event" :type "instance" :world nil)
                          (d/descriptor :type "action" :world nil)))

(def default-strat (att/strategy-tier 
                      (d/descriptor :name "object" :type "instance" :world nil)
                      (d/descriptor :name "fixation" :reason "color" :world nil)))

(def drive-strategy
  [[task-switch 11]
   [events-and-actions 10]
   ...
   [default-strat 1 0.1]])
```

In this example, the drive-strategy consists of a variety of partial strategies (to save space, only some of their definitions are shown. To define a strategy tier use the function `arcadia.utility.attention-strategy/strategy-tier` and pass in one or more descriptors. The tiers are assembled into a strategy which is a set of sequences. The first item in each sequence is a strategy tier. The second argument is the priority within the set (although shown as a vector, ordering in the set does not matter), and the optional third argument is the step for increasing priority on each cycle when the strategy tier does not select the focus. 

## The Default Attentional Strategy

Unless otherwise specified, the focus selector relies on the default attentional strategy. If that strategy does not pick out an element, then a random one is chosen to be the next focus of attention. This strategy is always operative and will be treated as a fall-back if a task-specific strategy fails to select an element. As shown in the code below, the default strategy prefers calls to adopt a new attentional strategy for the purposes of task switching. The ability to revoke an attentional strategy (i.e., to  rely solely on the default strategy) is also possible. After that, action requests, new objects, and fixation requests are preferred in that order. This default mode configures ARCADIA to observe the environment and act when requested even in the absence of any specific goal. 

```Clojure
(defn- select-focus-default 
  "A general default-mode attentional program that is useful across models."
  [expected] 
  (or (d/rand-element expected :name "task-configuration")
      (d/rand-element expected :type "action")
      (d/rand-element expected :name "object" :type "instance" :world nil)
      (d/rand-element expected :name "fixation")
      (g/rand-if-any (seq expected))))
```

A version of this strategy is built into the architecture and is used when no strategy is specified for a model (and when the model initially starts). You can use this as a strategy tier in your model by referring to the version defined as `arcadia.utility.attention-strategy/default-strategy`.

```Clojure 
(def default-strategy
  "A general default-mode attentional program that is useful across models."
  (strategy-tier (d/descriptor :name "task-configuration") 
                 (d/descriptor :name "object" :type "instance" :world nil)
                 (d/descriptor :name "fixation")
                 (d/descriptor :name d/ANY-VALUE)))
```

## Attentional Strategies in Models

All ARCADIA models must now use task-set representations. Attentional strategies are part of task-sets.

