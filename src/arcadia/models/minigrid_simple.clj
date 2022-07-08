(ns arcadia.models.minigrid-simple
  "Simple minigrid integration model for demo and development purposes."

  (:require [arcadia.utility.model :as model]
            [arcadia.utility.attention-strategy :as att]
            [arcadia.utility.display :refer [i#]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.general :as g]
            [arcadia.utility.opencv :as cv]
            [arcadia.utility.tasks :as t]
            [arcadia.utility.minigrid :as mg]
            [arcadia.architecture.registry :refer [get-sensor]]
            [arcadia.models.core]
            [clojure.java.io]))

(def ^:private default-environment "MiniGrid-MultiRoom-N6-v0")
;(def ^:private default-environment "MiniGrid-KeyCorridorS4R3-v0")


;; default attentional strategy works fine for this model

(def door-descriptor (d/object-descriptor "a door" :category "door"))
(def key-descriptor (d/object-descriptor "a key" :category "key"))
(def goal-descriptor (d/object-descriptor "a goal" :category "goal"))

(def object-descriptors [door-descriptor key-descriptor goal-descriptor])

(def toggle-descriptor (d/descriptor :name "event" :type "instance" :world nil
                                     :event-name "action" :action-command "toggle"))

(def done-descriptor (d/descriptor :name "event" :type "instance" :world nil
                                   :event-name "action" :action-command "done"))

(def entry-descriptor (d/descriptor :name "event" :type "instance" :world nil
                                    :event-name "enter-room"))

(def boundary-descriptors [#_toggle-descriptor entry-descriptor] )

(def red-door (d/extend-object-descriptor door-descriptor "a red door" :color "red" ))

(defn- task-switch [expected]
  (d/rand-element expected :name "switch-task" :type "action"))

(defn- toggle-event [expected]
  (d/rand-match toggle-descriptor expected))

(defn- done-event [expected]
  (d/rand-match done-descriptor expected))


(defn- entry-event [expected]
  (d/rand-match entry-descriptor expected))

;; place-holder for an episodic query
(defn- query-request [expected]
  (d/rand-element expected :name "episode" :type "instance" :world "query"))

(defn- subvocalize-request [expected]
  (d/rand-element expected :type "action" :name "subvocalize"))

(defn- new-object [expected]
  (or
   (d/rand-element expected :name "visual-new-object" :type "event" :world nil?)
   (d/rand-element expected :name "memorize" :type "action")))

(defn- basic-action [expected]
  (d/rand-element expected :type "action"))

(defn- pathing-action [expected]
    (d/rand-element expected :type "action" :path? true))

(defn- closed-door [expected]
  (d/rand-element expected :name "object" :type "instance" :world nil 
                  :category "door" :state "closed"))

(defn- door-key [expected]
  (d/rand-element expected :name "object" :type "instance" :world nil
                  :category "key"))

(defn- basic-strategy
  [expected]
  (or (d/rand-element expected :name "object" :type "instance" :world nil)
      (d/rand-element expected :name "fixation")
      (g/rand-if-any (seq expected))))

;; later we can add tasks that test different aspects of episodic
;; memory, but for now, it's just a trivial thing.

(defn minigrid-task []
  (let [minigrid-strategy [(att/partial-strategy task-switch 6)
                           (att/partial-strategy entry-event 5.1)
                          ; (att/partial-strategy toggle-event 5) 
                           (att/partial-strategy closed-door 4)
                           (att/partial-strategy door-key 4.5)
                           (att/partial-strategy pathing-action 3)
                           (att/partial-strategy basic-action 2)

                           (att/partial-strategy basic-strategy 1)]
        minigrid-response []]
    (t/task "minigrid"
            minigrid-strategy
            minigrid-response
            ;; never completes
            (d/descriptor :name false))))

;; wait for a query before doing returning to do anything interesting.
(defn recall-task []
  (let [recall-strategy [(att/partial-strategy task-switch 5)
                         (att/partial-strategy query-request 4)
                         (att/partial-strategy subvocalize-request 3)
                         (att/partial-strategy basic-strategy 1)]
        recall-response [(t/initial-response (t/subvocalize "what" :recall))]]
    (t/task "recall"
            recall-strategy
            recall-response
            (d/descriptor :type "instance" :world "episodic-memory" :query "what"))))

(defn- make-key-navpoint [episode content]
  ;; assume only one key
  (when-let [keyobj (d/first-element (-> episode :arguments :conceptual) :category "key")]
    ;; look through locations in the episode for the key
    (d/first-element
     (for [[loc descs] (seq (-> episode :arguments :spatial :contents))]
       (when (d/first-element descs :category "key")
         {:name "minigrid-navigation" :arguments {:goal (assoc-in keyobj [:arguments :region] loc)}}))
     :name "minigrid-navigation")))

(defn- make-door-navpoint [episode content]
  ;; assume only one key
  (when-let [doorobj (d/first-element (-> episode :arguments :conceptual) :category "door" :state "locked")]
    ;; look through locations in the episode for the locked door
    (d/first-element
     (for [[loc descs] (seq (-> episode :arguments :spatial :contents))]
       (when (d/first-element descs :category "door" :state "locked")
         {:name "minigrid-navigation" :arguments {:goal (assoc-in doorobj [:arguments :region] loc)}}))
     :name "minigrid-navigation")))

(defn find-key-task []
  (let [find-key-strategy [(att/partial-strategy task-switch 5)
                           (att/partial-strategy query-request 4)
                           (att/partial-strategy subvocalize-request 3)
                           (att/partial-strategy door-key 2.5)
                           (att/partial-strategy pathing-action 2)
                           (att/partial-strategy basic-strategy 1)]
        find-key-response [(t/initial-response (t/subvocalize "what" :find-key))
                           (t/sr-link [(d/descriptor :name "episode" :type "instance" :world "episodic-memory" :query "what")]
                                      make-key-navpoint)]]
    (t/task "find-key"
            find-key-strategy
            find-key-response
            (d/descriptor :name "query-failure" :type "instance" :world "episodic-memory" :query "what"))))


(defn return-to-locked-door-task []
  (let [return-to-locked-door-strategy [(att/partial-strategy task-switch 5)
                                        (att/partial-strategy query-request 4)
                                        (att/partial-strategy subvocalize-request 3)
                                        (att/partial-strategy pathing-action 2)
                                        (att/partial-strategy basic-strategy 1)]
        return-to-locked-door-response [(t/initial-response (t/subvocalize "what" :locked-door))
                           (t/sr-link [(d/descriptor :name "episode" :type "instance" :world "episodic-memory" :query "what")]
                                      make-door-navpoint)]]
    (t/task "return-to-locked-door"
            return-to-locked-door-strategy
            return-to-locked-door-response
            (d/descriptor :name "query-failure" :type "instance" :world "episodic-memory" :query "what"))))

(defn navigate-to-key-task []
  (let [navigate-to-key-strategy [(att/partial-strategy task-switch 6)
                                  (att/partial-strategy toggle-event 5)
                                  (att/partial-strategy door-key 4.5)
                                  (att/partial-strategy pathing-action 3)]
        navigate-to-key-response [(t/sr-link [(d/descriptor :name "episode" :type "instance" :world "episodic-memory" :query "what")]
                                             make-key-navpoint)]]
    (t/task "navigate-to-key"
            navigate-to-key-strategy
            navigate-to-key-response
            (d/descriptor :name false))))

;; whenever there's a red door, recall.
(def minigrid-task-hierarchy
  {:top "minigrid"
   :nodes
   {"minigrid"        "minigrid"
    ;"recall"          "recall"
    "find-key"        "find-key"
    "return-to-locked-door" "return-to-locked-door"} 
   :edges
   [;{:parent "minigrid" :trigger (d/descriptor :name "test" :cue "recall") :child "recall"}
    {:parent "minigrid" :trigger (d/descriptor :name "key" :cue "recall") :child "find-key"}
    {:parent "find-key" 
     :trigger (d/descriptor :name "minigrid-env-action" :type "environment-action" :action-command (mg/action-value "pickup")) 
     :child "return-to-locked-door"}]})

(def task-library (t/build-task-library (minigrid-task) (recall-task) (find-key-task) (return-to-locked-door-task)))

(def color-map
  {"red" java.awt.Color/RED
   "green" java.awt.Color/GREEN
   "blue" java.awt.Color/BLUE
   "purple" (java.awt.Color. 128 0 128)
   "yellow" java.awt.Color/YELLOW
   "grey" java.awt.Color/GRAY})

;; (defn foo [hm]
;;   (mapv (fn [x y] (vector x y)) (keys hm) (vals hm)))

(defn- event-string [e]
  (if (= (-> e :arguments :event-name) "action")
    (if (= (-> e :arguments :action-command) "toggle")
      "open-door"
      (-> e :arguments :action-command))
    (-> e :arguments :event-name)))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn sensor-setup []
  (model/setup
   (model/add stable-viewpoint)
   (model/add minigrid-sensor)))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn component-setup []
  (model/setup
   ;; minigrid specific
   (model/add minigrid.perception {:sensor (get-sensor :minigrid-sensor)})
   (model/add minigrid.wallfollower)
   (model/add minigrid.pathfinder)
   (model/add minigrid.actor)
   (model/add minigrid.segmenter {:sensor (get-sensor :minigrid-sensor)})
   (model/add minigrid.mapper {:sensor (get-sensor :minigrid-sensor)})
   (model/add minigrid.highlighter)
   (model/add minigrid.egocentric-map {:sensor (get-sensor :minigrid-sensor)})
   (model/add minigrid.episode-binder)
   (model/add minigrid.action-detector)
   ;(model/add minigrid.test-query)
   (model/add minigrid.key-query)
   (model/add minigrid.enter-room-detector)
   (model/add minigrid.object-navigator)

   ;; task management
   (model/add sr-link-processor)
   (model/add initial-response-generator)
   (model/add task-hierarchy {:hierarchy minigrid-task-hierarchy})
   (model/add task-ltm {:tasks task-library})

   ;; memory management
   (model/add working-memory {:decay-rate 0.025 :refresh-rate 0.025 :randomness 0.5})
   (model/add new-object-recorder)
   (model/add spatial-ltm)
   ;; XXX: boundaries at toggle actions.
   (model/add episodic-ltm {:episode-boundaries boundary-descriptors})
   (model/add conceptual-ltm)
   (model/add episodic-memory {:event-lifespan 1 :descriptors object-descriptors})

   ;; visual processing
   (model/add object-file-binder)
   (model/add vstm)
   (model/add object-locator {:sensor (get-sensor :minigrid-sensor)
                              :use-hats? false})

   ;; information display
   ; (model/add display.objects {:x 0 :y 0})
   #_(model/add display.scratchpad
                {:x 1500 :y 20
                 :sensor (get-sensor :stable-viewpoint)
                 :elements [[(i# (-> % :content (d/first-element :name "episode")
                                     :arguments :spatial :contents
                                     (#(map (fn [k] (vector k (get % k))) (keys %)))
                                   ;(->> (into []))
                                     ))]]})

   (model/add display
              {:display-name "Events"
               :x 700 :y 150
               :panel-width 300
               :panel-height 50
               :elements
               [["Current"
                 (i# (-> % :content (d/first-element :name "episode" :type "instance" :world nil)
                         :arguments :temporal
                         (#(map event-string %))))]
                ["Recalled"
                 (i# (-> % :content (d/first-element :name "episode" :type "instance" :world "episodic-memory")
                         :arguments :temporal
                         (#(map event-string %))))]]}
              :event-display)


   (model/add display
              {:display-name "Navigation Goal"
               :x 700 :y 250
               :panel-width 400
               :panel-height 50
               :elements
               [["Object"
                 (i# (-> % :content (d/first-element :name "minigrid-navigation" :type "action" :world nil)
                         :arguments :goal :arguments (#(select-keys % [:category :state :color]))))]
                ["Location"
                 (i# (-> % :content (d/first-element :name "minigrid-navigation" :type "action" :world nil)
                         :arguments :goal :arguments :region (#(select-keys % [:x :y]))))]]}
              :navigation-display)

   (model/add display
              {:display-name "State"
               :x 700 :y 350
               :panel-width 400
               :panel-height 75
               :elements
               [["Cycle" (i# (-> % :cycle))]
                ["Task"
                 (i# (-> % :content (d/first-element :name "task" :type "instance" :world "task-wm")
                         :arguments :instance-name))]
                ["Focal Object"
                 (i# (when (d/element-matches? (:focus %) :name "object" :world nil)
                       (select-keys (-> % :focus :arguments) [:category :state :color])))]]}
              :state-display)

   ; (model/add display.scratchpad)

   (model/add display
              {:panels [["Minigrid"
                         (i# (:image %))]
                        ["Map Grid"
                         (i# (-> % :content (d/first-element :name "spatial-map" :perspective "allocentric") :arguments :layout
                                 (cv/in-range [1 0 0] [10 10 10])
                                 (cv/cvt-color cv/COLOR_GRAY2BGR)
                                 (cv/convert-to cv/CV_8S)
                                ;(cv/resize [400 400] :interpolation cv/INTER_NEAREST)
                                 ))]
                        ["Current Episode"
                         (i# (-> % :content (d/first-element :name "map-grid") :arguments :map
                                 (cv/in-range [2 0 0] [2 10 10])
                                 (cv/cvt-color cv/COLOR_GRAY2BGR)
                                 (cv/convert-to cv/CV_8S)
                                 ;(cv/resize [400 400] :interpolation cv/INTER_NEAREST)
                                 ))
                         :glyphs [[(i# (-> % :content (d/first-element :name "episode" :world nil)
                                           :arguments :spatial :contents
                                           (#(map (fn [k] (vector k (first (get % k)))) (keys %)))))
                                   :glyph-value (i# (-> % :glyph first))
                                   :color (i# (-> % :glyph second :arguments :color color-map))
                                   :fill-color (i# (when (#{"closed" "locked"} (-> % :glyph second :arguments :state))
                                                     (-> % :glyph second :arguments :color color-map)))]]]
                        ["Recalled Episode"
                         (i# (-> % :content (d/first-element :name "map-grid") :arguments :map
                                 (cv/in-range [2 0 0] [2 10 10])
                                 (cv/cvt-color cv/COLOR_GRAY2BGR)
                                 (cv/convert-to cv/CV_8S)
                                 ;(cv/resize [400 400] :interpolation cv/INTER_NEAREST)
                                 ))
                         :glyphs [[(i# (-> % :content (d/first-element :name "episode" :world "episodic-memory")
                                           :arguments :spatial :contents
                                           (#(map (fn [k] (vector k (first (get % k)))) (keys %)))))
                                   :glyph-value (i# (-> % :glyph first))
                                   :color (i# (-> % :glyph second :arguments :color color-map))
                                   :fill-color (i# (when (#{"closed" "locked"} (-> % :glyph second :arguments :state))
                                                     (-> % :glyph second :arguments :color color-map)))]]]]

               :element-type :image
               :image-width 300
               :image-height 300
               :sensor (get-sensor :minigrid-sensor)
               :display-name "Minigrid" :x 0 :y 150})))

(defn example-run
  ([]
   (example-run 50))

  ([cycles]
    (example-run cycles default-environment false))
  ([cycles env-str record?]
   ;; use resolve to avoid circular dependencies for the example run
   ((resolve 'arcadia.core/startup)
    'arcadia.models.minigrid-simple 'arcadia.simulator.environment.minigrid
    {:environment env-str}
    ;{}
    cycles
    :record? record?)))

;(arcadia.models.minigrid-simple/example-run 300 "MiniGrid-MultiRoom-N6-v0" false)
;(arcadia.models.minigrid-simple/example-run 200 "MiniGrid-KeyCorridorS4R3-v0" false)