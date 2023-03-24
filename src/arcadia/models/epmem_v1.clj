(ns arcadia.models.epmem-v1
  "This is a model that watches some images go by and reports whether it has or 
  has not seen that image before. It is designed to be a basic test of episodic
  memory encoding and retrieval."
  (:require [arcadia.utility.attention-strategy :as att]
            [arcadia.utility.display :refer [blank-canvas i#]]
            [arcadia.utility.general :as g]
            [arcadia.utility.model :as model]
            [arcadia.utility.objects :as obj]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.tasks :as t]
            [arcadia.utility.geometry :as geo]
            [arcadia.models.core]
            [arcadia.architecture.registry :refer [get-sensor]]))

(def epmem-size-similarity-threshold 0.8)
(defn- size-comparator [obj1 obj2]
  (obj/similar-size-contours? obj1 obj2 :threshold  epmem-size-similarity-threshold))

(def stimulus-descriptors
  [(d/object-descriptor "a triangle" :shape "triangle")
   (d/object-descriptor "a square" :shape "square")
   (d/object-descriptor "a circle" :shape "circle")])

;; if the inhibition is inclusive of the specified point, then
;; the candidate is inhibited if it contains that point.
(defn- inhibits? [inhibitor candidate locations]
  (when-let [region (obj/get-region candidate locations)]
    (if (= (-> inhibitor :arguments :mode) "include")
      (geo/intersect? region (-> inhibitor :arguments :region))
      (not (geo/intersect? region (-> inhibitor :arguments :region))))))

(defn- uninhibited-fixation [fixations inhibitions locations]
  (when (seq inhibitions)
    (g/rand-if-any (filter (fn [x]
                             (not-any? #(inhibits? % x locations) inhibitions))
                           fixations))))

(defn- task-switch [expected]
  (d/rand-element expected :name "switch-task" :type "action"))
                        
;; see note below at model/add episodic-ltm
;; (defn- relevant-event [expected]
;;   (d/rand-element expected :name "event" :type "instance" :activity "push-button"))

(defn- basic-strategy
  [expected]
  (or (d/rand-element expected :type "action")
      (d/rand-element expected :name "object" :type "instance" :world nil)
      (d/rand-element (att/remove-object-fixations (d/filter-elements expected :name "fixation") expected))
      (d/rand-element expected :name "fixation")
      (g/rand-if-any (seq expected))))

;; later we can add tasks that test different aspects of episodic 
;; memory, but for now, it's just a trivial thing.

(defn epmem-task []
  (let [epmem-strategy [(att/partial-strategy task-switch 5)
                        ;; see note below at model/add episodic-ltm
                        ;; (att/partial-strategy relevant-event 4)
                        (att/partial-strategy basic-strategy 1 0.1)]
        epmem-response []]
    (t/task "epmem"
            epmem-strategy
            epmem-response
            ;; never completes
            (d/descriptor :name false))))

(def epmem-task-hierarchy
  {:top "epmem"
   :nodes
   {"epmem"        "epmem"}})

(def task-library (t/build-task-library (epmem-task)))

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
   (model/add image-segmenter {:sensor (get-sensor :stable-viewpoint) :use-colors? true})
   (model/add highlighter.color {:sensor (get-sensor :stable-viewpoint)})
   (model/add highlighter.basic-shape)
   (model/add object-file-binder)
   (model/add vstm {:diff-size-comparator size-comparator})
   (model/add object-locator {:sensor (get-sensor :stable-viewpoint)
                              :use-hats? false})
   (model/add display.objects {:x 0 :y 0
                               :elements
                               [["color"
                                 (i# (-> % :panel :arguments :color))]
                                ["shape"
                                 (i# (-> % :panel :arguments :shape))]
                                ["tracked"
                                 (i# (-> % :panel :arguments :tracked?))]]})
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
              {:display-name "Match?"
               :cols 1 :rows 1
               :panel-width 100
               :element-type :text :center? true
               :x 0
               :y 750
               :elements
               [[(i# (-> %
                         :content
                         (d/first-element :name "episode" :world "episodic-memory" :type "instance")
                         ((fn [x] (if x "Yes" "No")))))]]}
              :match-display)

   (model/add new-object-recorder)
   (model/add sr-link-processor)
   (model/add initial-response-generator)
   (model/add task-hierarchy {:hierarchy epmem-task-hierarchy})
   (model/add task-ltm {:tasks task-library})
   (model/add working-memory {:decay-rate 0.025 :refresh-rate 0.025 :randomness 0.5})
   (model/add display.status
              {:display-name "Status" :panel-width 400 :panel-height 150
               :caption-width 110 :precision 2
               :x 0 :y 550
               :elements
               [["Task" task-name]]})

   (model/add allocentric-layout)
   (model/add egocentric-layout)
   (model/add spatial-ltm)
   (model/add conceptual-ltm)
   ;; XXX: i think the ideal way to handle this would be at the attention level, so episodic LTM
   ;; would encode an episode whenever an event is the focus of attention. the problem is that in 
   ;; this model, if you wait for an event to be the focus, the screen is blank and the episodic
   ;; content disappears. one option is to determine the scope of episodic content so that there
   ;; is a persistent short-term binding that lasts beyond perceptual availability. here, we just
   ;; specify an episode boundary that will work for the current environment properties. this is, 
   ;; as the kids say, a grotesque hack to avoid premature decision making. 
   (model/add episodic-ltm {:episode-boundaries [(d/descriptor :name "push-button" :type "action")]})
   (model/add event-memory {:event-lifespan 1 :descriptors stimulus-descriptors})
   (model/add episode-binder)
   (model/add action-detector)

   (model/add trial-starter)
   (model/add trial-responder)
   (model/add button-pusher)
   (model/add vstm-enumerator-simple {:vstm-size 4})

   (model/add display.controls {:x 0 :y 600})
   (model/add display.scratchpad
                {:x 1500 :y 20
                 :sensor (get-sensor :stable-viewpoint)})
   (model/add
    display
    {:x 50 :y 50
     :display-name "Egocentric Layout"
     :element-type :image :center? true
     :image-scale 0.5
     :sensor (get-sensor :stable-viewpoint)
     :elements
     [[(blank-canvas :black)]]
     :glyphs
     [[(i# (-> % :content
               (d/filter-elements :name "object-location")))
       :glyph-value (i# (-> % :glyph :arguments :region))
       :color :green]]})

   ))

(defn example-run
  [trials & {:keys [max-cycles record?]}]
   ;; use resolve to avoid circular dependencies for the example run
  ((resolve 'arcadia.core/startup)
   'arcadia.models.epmem-v1
   'arcadia.simulator.environment.epmem
   {:n-trials trials}
   max-cycles
   :record? record?))