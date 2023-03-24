(ns arcadia.models.cued-task-switching-mp
  "This is a model of cued task switching for basic magnitude/parity tasks.
   It provides an example of how to use subvocalization to drive task switches
   in a relatively straightforward scenario."
  (:require [arcadia.utility.attention-strategy :as att]
            [arcadia.utility.display :refer [i#]]
            [arcadia.utility.model :as model]
            [arcadia.utility.objects :as obj]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.tasks :as t]
            [arcadia.models.core]
            [arcadia.architecture.registry :refer [get-sensor]]))

(def cts-size-similarity-threshold 0.8)
(defn- size-comparator [obj1 obj2]
  (obj/similar-size-contours? obj1 obj2 :threshold  cts-size-similarity-threshold))

(defn- select-focus-task
  "Select a focus of attention that prefers actions in pursuit of a
  specific task."
  [kind expected]
  (or (d/rand-element expected :type "action" :task kind)
      (d/rand-element expected :type "action" :name "subvocalize")))

(defn- task-switch [expected]
  (d/rand-element expected :name "switch-task" :type "action"))

(defn- push-button [bid task-kw]
 {:name "push-button"
  :arguments {:button-id bid
              :task task-kw
              :effector :pointer}
  :world nil
  :type "action"})

(defn cue-task []
  (let [cue-response [(t/sr-link [(d/descriptor :name "object-property" :property :character :value "M")]
                                 (t/subvocalize "magnitude" :cue))
                      (t/sr-link [(d/descriptor :name "object-property" :property :character :value "P")]
                                 (t/subvocalize "parity" :cue))]
        cue-strategy [(att/partial-strategy task-switch 5)
                      (att/partial-strategy (partial select-focus-task :cue) 4)
                      (att/partial-strategy att/default-strategy 1 0.1)]]
    (t/task "cue"
            cue-strategy
            cue-response
            ;; never completes
            (d/descriptor :name false))))

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

(defn par-task []
  (let [par-response [(t/sr-link [(d/descriptor :name "object-property" :property :parity :value :odd)]
                                 (push-button :b-odd-low :parity))
                      (t/sr-link [(d/descriptor :name "object-property" :property :parity :value :even)]
                                 (push-button :b-even-high :parity))]
        par-strategy [(att/partial-strategy task-switch 5)
                      (att/partial-strategy (partial select-focus-task :parity) 4)
                      (att/partial-strategy att/default-strategy 1 0.1)]]
    (t/task "parity"
            par-strategy
            par-response
            (d/descriptor :name "push-button" :type "environment-action"))))

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

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn sensor-setup []
  (model/setup
   (model/add stable-viewpoint)))

(def task-name
  (i# (let [task (d/first-element (:content %) :name "task")]
        (format "%s(%s)" (-> task :arguments :instance-name) (-> task :arguments :handle)))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn component-setup []
  (model/setup
   (model/add image-segmenter {:sensor (get-sensor :stable-viewpoint)})
   (model/add object-file-binder)
   (model/add vstm {:diff-size-comparator size-comparator})
   (model/add object-locator {:sensor (get-sensor :stable-viewpoint)
                              :use-hats? false})
   (model/add display.objects {:x 0 :y 0})
   (model/add display
              {:display-name "Input"
               :cols 1 :rows 1
               :image-width 256 :image-height 256
               :element-type :image :center? true
               :x 500
               :y 300
               :elements
               [[(i# (-> %
                         :content
                         (d/first-element :name "image-segmentation")
                         :arguments
                         :image))]]}
              :input-display)
   (model/add display
              {:display-name "Subvocalization"
               :x 500 :y 200
               :panel-width 200
               :panel-height 25
               :elements
               [["Text"
                 (i# (when (-> % :focus (d/element-matches? :name "subvocalize" :type "action"))
                       (-> % :focus :arguments :lexeme)))]]}
              :subvocalization-display)

   (model/add reporter.letter)
   (model/add motion-planner)
   (model/add new-object-recorder)
   (model/add number-identifier)
   (model/add reporter.number-magnitude)
   (model/add reporter.number-parity)
   (model/add highlighter.saliency {:segment-sensor (get-sensor :stable-viewpoint)
                                    :map-sensor (get-sensor :stable-viewpoint)})
   (model/add sr-link-processor)
   (model/add initial-response-generator)
   (model/add task-hierarchy {:hierarchy cts-task-hierarchy})
   (model/add task-ltm {:tasks task-library})

   (model/add two-button-display {:x 500 :y 100})
   (model/add bottom-up-saliency {:sensor (get-sensor :stable-viewpoint)})
   (model/add working-memory)
   (model/add wm-handle-refresher)
   (model/add display.status
              {:display-name "Status" :panel-width 300 :caption-width 70 :precision 2
               :x 100 :y 400
               :elements
               [["Task" task-name]]})))



(defn example-run
  ([]
   (example-run 200))
  ([cycles]
   ;; use resolve to avoid circular dependencies for the example run
   ((resolve 'arcadia.core/startup)
    'arcadia.models.cued-task-switching-mp
    'arcadia.simulator.environment.cued-task-switching
    {:n-trials 5
     :cue-interval 900
     :stimulus-interval 2000
     :intratrial-interval 100
     :intertrial-interval 100}
    cycles)))
