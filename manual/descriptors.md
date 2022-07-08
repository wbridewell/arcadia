Descriptors
===

## What's an Interlingua Element?
ARCADIA consists of many components which implement a wide range of functions ranging from image processing and basic vision to semantic labeling, enumeration, and memory maintenance. While each of these functions make use of different sorts of data representations, a component must be able to receive input from other components for processing, and it must be able to produce output to other components for later processing. To deal with this, ARCADIA uses a standard data format called an interlingua element, which is a hash-map containing the top-level keywords `:name`, `:arguments`, `:type`, `:source`, and `:world`. The `:arguments` value of an interlingua element is itself a hash map containing arbitrary data structures specific to that element.

Here's what an interlingua element looks like in Clojure:
```Clojure
{:name "object"
 :arguments {:color "blue"
             :shape "rectangle"}
 :type "instance"
 :source arcadia.component.VSTM
 :world "vstm"}
 ```

## Descriptors: the basics
Since interlingua elements are just Clojure hash-maps, Clojure's core sequence operations already work over them. However, the nested structure of interlingua elements can make using these core operations unwieldily, and even problematic. The descriptor utilities solve this problem by implementing the core sequence operations provided by Clojure in a clean, efficient, and user-friendly manner.

### What is a descriptor?
Under the hood, descriptors are partial interlingua elements; they are hash maps with keys and values. Unlike interlingua elements, descriptors are interpreted as predicates for pattern matching as opposed to raw data.

Examples/
```Clojure
;; match any interlingua containing a :name key with a string value of "fixation"
(def fixation-descr {:name "fixation"})

;; match any interlingua with a :color argument with a string value of "blue"
(def blue-descr {:arguments {:color "blue"}}
```

### Making Descriptors
While it's possible to use the bracket syntax to create descriptors for pattern matching, ARCADIA provides its own functionality for descriptor making. The function `descriptor` takes any number of key-value pairs, and organizes them into the basic format of an interlingua element. This is done using one simple rule: if the key is a top-level key of an interlingua element, it is placed in the top-level of the descriptor. Otherwise, it is included inside the `:arguments` map of the descriptor. The descriptors above can be created using the following function calls:

```Clojure
(def fixation-descr (descriptor :name "fixation")

(def blue-descr (descriptor :color "blue")
```

### Using Descriptors
The function `descriptor-matches` takes a descriptor and an interlingua element, and tests if the descriptor matches the element. So, the function call

```Clojure
(descriptor-matches? fixation-descr
                     {:name "fixation"
                      :arguments {:reason "color"}
                      :type "instance"
                      :source arcadia.component.ColorHighlighter
                      :world nil})
```
returns `true`, while the function call
```Clojure
(descriptor-matches? fixation-descr
                     {:name "object"
                      :arguments {:color "red"}
                      :type "vstm"
                      :source arcadia.component.VSTM
                      :world nil})
```
returns `nil`.

More likely though, it would be inconvenient (and against the functional paradigm) to define a global variable every time you match a descriptor. While you could include a call to descriptor inside each call to `descriptor-matches?`, ARCADIA provides the macro `element-matches?` to construct descriptors for matching at compile time. In fact, ARCADIA provides both a function and a macro version of each core sequence operation for just this purpose. All of the core searching functions in Clojure are listed below, along with their descriptor function and macro analogues.

| Clojure | Function | Macro |
|-|-|-|
| = | descriptor-matches? | element-matches? |
| filter | filter-matches | filter-elements |
| remove | remove-matches | remove-elements |
| find-first | first-match | first-element |
| some | some-match | some-element |
| every? | every-matches | every-element |
| not-any? | not-any-match | not-any-element |
| rand-nth + filter | rand-match | rand-element |

To get a good handle on how to write code using the descriptor utilities, let's compare the Clojure code for different searching operations over interlingua elements side-by-side to the equivalent code using the descriptor utilities. Notice that for very simple predicates the code is about the same length whether the descriptor utilities are used or not; but as the predicates get more complex, the descriptor utilities substantially cut down the amount of code required, and in turn make the code much more approachable. In the following, we'll use the variable e as a stand-in for an interlingua element, and the variable content for a sequence of interlingua elements.

**Predication**
```Clojure
;;; Equivalent Clojure and Descriptor formats
(= (:name e) "object")
(element-matches? e :name "object")

(= (:color (:arguments e)) "blue")
(element-matches? e :color "blue")

(and (= (:name e) "object")
     (= (:color (:arguments e)) "blue"))
(element-matches? e :name "object" :color "blue")
```

**Searching**
```Clojure
;;; Equivalent Clojure and Descriptor formats
(filter #(= (:name %) "object") content)
(filter-elements content :name "object")

(find-first #(= (:color (:arguments %)) "blue") content)
(first-element content :color "blue")

(filter #(and (= (:name e) "object")
              (= (:color (:arguments e)) "blue"))
        content)
(filter-elements content :name "object" :color "blue")
```

**Arbitrary Constraints**
In addition to rote pattern matching, descriptors also support using predicates as values. When the descriptor is applied to an interlingua, all of the predicated values are applied to the interlingua element's corresponding value.

```Clojure
;;; Equivalent Clojure and Descriptor formats
(rand-nth (filter #(and (= (:name %) "object")
                        (= (:world %) nil)
                        (pos? (:precise-delta-x (:arguments %)))
                        (< (:precise-delta-y (:arguments %)) -1))
                  content))

(rand-element content :name "object" :world nil :precise-delta-x pos?
              :precise-delta-y #(< % -1))
```
And because Clojure supports using sets as predicates, descriptors also support using sets for disjunctive matching:
```Clojure
;;; Equivalent Clojure and Descriptor formats
(find-first #(and (= (:name e) "object")
                  (= (:type e) "instance")
                  (or (= (:color (:arguments e)) "blue")
                      (= (:color (:arguments e)) "red"))
                  (= (:shape-description (:arguments e)) "circle"))
            content)

(first-element content :name "object" :type "instance"
               :color #{"red" "blue"}
               :shape-description "circle"
```

**Nested Interlingua Elements**

Using the descriptor utilities, it's also possible to easily search for specific types of interlingua nested inside of other interlingua without needing to nest anonymous functions inside of each other.

```Clojure
;;; Equivalent Clojure and Descriptor formats
(filter #(and (= (:name %) "memorize")
              (= (:type %) "action")
              (= (:name (:element (:arguments %))) "object-property")
              (= (:type (:element (:arguments %))) "instance")
              (= (:property (:arguments (:element (:arguments %))))
                 :character)
              (= (:value (:arguments (:element (:arguments %))))
                 "P")
        content)
(filter-elements content :name "memorize" :type "action"
                 :element #(element-matches? % :name "object-property"
                                             :type "instance"
                                             :property :character
                                             :value "P"))

(not-any? #(and (= (:name %) "relation")
                (= (:type %) "instance")
                (= (:world %) nil)
                (some (fn [a] (and (= (:name a) "object")
                                   (= (:type a) "instance")
                                   (= (:world a) "vstm")
                                   (= (:color (:arguments a)) "red")
                                   (= (:shape (:arguments a)) "circle")
                      (:arguments (:arguments %))))
          content)
(not-any-element content :name "relation" :type "instance" :world nil
                 :arguments #(some-element % :name "object" :type "instance"
                                           :world "vstm" :color "red"
                                           :shape "circle"))

(some #(and (or (= (:name %) "event")
                (= (:name %) "event-stream"))
            (or (= (:world %) nil)
                (= (:world %) "episodic-memory"))
            (= (:type %) "instance")
            (= (:event-name (:arguments %)) "motion")
            (= (:name (first (:objects (:arguments %)))) "object")
            (= (:type (first (:objects (:arguments %)))) "instance")
            (= (:world (first (:objects (:arguments %)))) "vstm")
            (= (:color (:arguments (first (:objects (:arguments %))))) "green")
            (= (:shape (:arguments (first (:objects (:arguments %))))) "rectangle")
            (pos? (:precise-delta-x (:arguments (first (:objects (:arguments %))))))
            (> (:precise-delta-y (:arguments (first (:objects (:arguments %))))) 1.5)
            (<= (:radius (:region (:arguments (first (:objects (:arguments %)))))) 50.0)
            (<= (:x (:center (:region (:arguments (first (:objects (:arguments %)))))))
                200.0)
      content)
(some-element content :name #(or (= % "event") (= % "event-stream"))
              :world #(or (nil? %) (= % "episodic-memory"))
              :type "instance"
              :event-name "motion"
              :objects (fn [o] (element-matches? (first o) :name "object" :type "instance"
                                     :world "vstm" :color "green" :shape "rectangle"
                                     :precise-delta-x pos? :precise-delta-y #(> % 1.5)
                                     :region #(and (<= (:radius %) 50.0)
                                                   (<= (:x (:center %)) 200.0))))
```

## Use in Components

The `receive-focus` function of components are primarily written in three stages: filtering input relevant to the component, processing the input according to some function, and finally producing output in the form of interlingua elements. Descriptors are predominantly useful in the first stage, since components are usually responsive to either a single interlingua element, or to a set of specific interlingua elements sharing some feature. A template for a component's `receive-focus` function is provided below; if you're writing a component, take a look above at the `first-element` and `filter-elements` macros.

```Clojure
(receive-focus [component focus content]
  (let [input (filter-elements content ...)]
    (format-output (...some function calls... input))
```

## Use in Attentional Strategies
Attentional strategies are functions that select the highest-priority interlingua among a set of interlingua elements called accessible content. This ranking is typically implemented in a large or statement, with each successive disjunct being lower in priority to its predecessor. Each disjunct is a subset of accessible content obtained by selecting the first available (or a random) element matching some constraint: this pattern is perfectly suited for the `first-element` and `rand-element` macros.

Here's a simplified example of how these macros can be used in an attentional strategy:
```Clojure
(defn select-focus [expected]
  [(or (first-element expected :name "gaze" :saccading? true)
       (first-element expected :name #{"memorize" "memory-update"})
       (first-element expected :name "scan" :ongoing? true)
       (rand-element expected :name "object" :type "instance" :world nil)
       (first-element expected :name "saccade")
       (rand-element expected :name "scan"))
       (rand-element expected :name "scan" :relation #(= (:context %) "amount")))

       ;;;;;Fixations;;;;;;;;;
       (rand-element expected :name "fixation" :reason "gaze")

       ...

       (rand-element expected :name "fixation")
       (rand-if-any (seq expected)))
     expected]))
```

## Use in Object Recognition
Descriptors are not only useful for searching through interlingua elements, but also can have a special use with regard to objects: when an interlingua element describing some object in the visual field matches some descriptor, it can be labeled accordingly. Descriptors can be labeled using the function `label-descriptor`:

```Clojure
(def red-descr (label-descriptor (descriptor :name "object" :type "instance" :color "red") "the red object"))
```
which evaluates to the descriptor
```Clojure
{:name "object", :type "instance", :arguments {:color "red"}, :label "the red object"}
```
In the context of objects, though, it's often easier to use the descriptor constructor `object-descriptor`, which takes a label and any number of key-value pairs, and creates a descriptor for an instance of an object matching that descriptor. So, the code above can be abbreviated to:

```Clojure
(def red-descr (object-descriptor "the red object" :color "red"))
```

Sometimes it's useful to add constraints to descriptors you've already defined. It's possible to extend descriptors in order to create a sort of class hierarchy over object types using the functions `extend-descriptor` and its wrapper `extend-object-descriptor`. Below, we'll extend the `red-descr` we made above to include sub-types for red circles and red squares using each of these functions.

```Clojure
(def red-circle-descr (label-descriptor (extend-descriptor red-descr :shape "circle") "the red circle"))
(def red-square-descr (extend-object-descriptor red-descr "the red square" :shape "square"))
```

Finally, once we've created all of the object descriptors we need, we can identify objects based on which descriptor they match to using the function `get-label`:

```Clojure
(get-label {:name "object"
            :arguments {:color "red" :shape "square"}
            :type "instance"
            :source arcadia.component.VSTM
            :world "vstm"}
           (list red-circle-descr red-square-descr red-descr))
```
evaluates to `"the red square"`,
```Clojure
(get-label {:name "object"
            :arguments {:color "red" :shape "octagon"}
            :type "instance"
            :source arcadia.component.VSTM
            :world "vstm"}
           (list red-circle-descr red-square-descr red-descr))
```
evaluates to `"the red object"`, and
```Clojure
(get-label {:name "object"
            :arguments {:color "blue" :shape "square"}
            :type "instance"
            :source arcadia.component.VSTM
            :world "vstm"}
           (list red-circle-descr red-square-descr red-descr))
```
evaluates to `nil`.
