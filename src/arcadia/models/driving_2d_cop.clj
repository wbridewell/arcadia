(ns arcadia.models.driving-2d-cop
  (:require [arcadia.architecture.registry :refer [get-sensor]]
            (arcadia.utility [model :as model]
                             [attention-strategy :as att]
                             [display :refer [i#]]
                             [descriptors :as d]
                             [general :as g]
                             [objects :as obj]
                             [tasks :as tasks])
            arcadia.models.core
            [arcadia.simulator.environment [jbox2d :as jbox2d]]
            [arcadia.vision.regions :as reg]
            [clojure.math.numeric-tower :as math])
  (:import [java.awt Color]))

(def COP? true)
(def SELF-LABEL "B")
(def display-scale 0.4)
(def env-args
  {:width 1200
   :height 600
   :scale 1.25
   :keyboard-force 100})

;; Used by VSTM to compare sizes of objects
(def driving-size-similarity-threshold 0.1)
(defn- size-comparator [obj1 obj2]
  (obj/similar-size? (-> obj1 :arguments :region)
                     (-> obj2 :arguments :region)
                     driving-size-similarity-threshold))

(def ball-descriptor (d/object-descriptor "a ball" :shape-description "circle"))
(def self-descriptor (d/extend-object-descriptor ball-descriptor "me" :color "blue" :tracked? true))
(def uncle-descriptor (d/extend-object-descriptor ball-descriptor "my uncle" :color "yellow" :tracked? true))
(def cop-descriptor (d/extend-object-descriptor ball-descriptor "cop" :color "red"))
(def witness-descriptor (d/extend-object-descriptor ball-descriptor "witness" :color "green"))
(def dead-descriptor (d/extend-object-descriptor ball-descriptor "dead" :color "gray"))

(def object-descriptors [self-descriptor uncle-descriptor cop-descriptor ball-descriptor])

(defn get-fixations-for-descriptor
  "This function returns all fixation requests to a region shared by an
  object which matches the provided object-descriptor."
  ([content object-descriptor]
   (get-fixations-for-descriptor content object-descriptor
                                 (obj/get-vstm-objects content :tracked? true)))
  ([content object-descriptor vstm]
   (mapcat #(att/get-fixations-to-region content (obj/get-region % content))
           (d/filter-matches object-descriptor vstm))))

;------------------------------Strategy Functions --------------------------------------------------
;TODO

(defn- uncle-fixations [expected]
  (let [uncle-descriptors (get-fixations-for-descriptor expected uncle-descriptor)]
    (or (d/rand-element uncle-descriptors :reason "maintenance")
        (d/rand-element uncle-descriptors :reason "color"))))

(defn- gaze [expected]
  (d/rand-element expected :name "gaze" :saccading? true))

(defn- memory [expected]
  (d/rand-element expected :name #(or (= % "memorize") (= % "memory-update")) :type "action"))

(defn- cop-fixations [expected]
  (let [cop-descriptors (get-fixations-for-descriptor expected cop-descriptor)]
    (or (d/rand-element cop-descriptors :reason "maintenance")
        (d/rand-element cop-descriptors :reason "color"))))

(defn- remember-cop [expected]
  (let [cop-descriptors (get-fixations-for-descriptor expected cop-descriptor)]
    (d/rand-element expected :world "vstm" :color "red")))

(defn- self-fixations [expected]
  (let [self-descriptors (get-fixations-for-descriptor expected self-descriptor)]
    (or (d/rand-element self-descriptors :reason "maintenance")
        (d/rand-element self-descriptors :reason "color"))))

(defn- new-color-fixations [expected]
  (d/rand-element (att/remove-object-fixations expected expected) :name "fixation" :reason "color"))

(defn- default-strat [expected]
   (d/rand-element expected :name "fixation" :reason "color"))

(defn- object [expected]
  (or (d/rand-element expected :name "object" :type "instance" :world nil :color "green" :shape-description "circle")
      (d/rand-element expected :name "object" :type "instance" :world nil :color "yellow" :shape-description "circle")
      (d/rand-element expected :name "object" :type "instance" :world nil :color "blue" :shape-description "circle")
      (d/rand-element expected :name "object" :type "instance" :world nil :color "red" :shape-description "circle")
      (d/rand-element expected :name "object" :type "instance" :world nil :color "gray" :shape-description "circle")))

(defn- saccade [expected]
  (d/rand-element expected :name "saccade"))

(defn- fixation-gaze [expected]
  (d/rand-element expected :name "fixation" :reason "gaze"))

(defn- button-strat [expected]
  (d/rand-element expected :name "push-button"))

;; XXX this wasn't ever effective because :priority was in the wrong place in
;; the interlingua element. when you opt for a high priority action, then you
;; end up only pushing the button. this model needs some fine tuning.
(defn- actions [expected]
  (d/rand-element expected :type "action" :priority "high"))

(defn- task-switch [expected]
  (d/rand-element expected :name "switch-task" :type "action"))

(def drive-strategy
  [(att/partial-strategy task-switch 11)
   (att/partial-strategy actions 10)
   (att/partial-strategy gaze 9)
   (att/partial-strategy memory 7)
   (att/partial-strategy object 6)
   (att/partial-strategy saccade 5)
   (att/partial-strategy new-color-fixations 4)
   (att/partial-strategy uncle-fixations 3)
   (att/partial-strategy button-strat 2 0.5)
   (att/partial-strategy default-strat 1 0.1)])

(def kill-uncle-strategy
  [(att/partial-strategy task-switch 11)
   (att/partial-strategy actions 10)
   (att/partial-strategy memory 7)
   (att/partial-strategy object 6)
   (att/partial-strategy saccade 5)
   (att/partial-strategy fixation-gaze 6)
   (att/partial-strategy new-color-fixations 4)
   (att/partial-strategy self-fixations 1 0.1)
   (att/partial-strategy cop-fixations 4)
   (att/partial-strategy button-strat 2 1)
   (att/partial-strategy default-strat 1 0.1)])



;---------------------------------------------------------------------------------------------------
(defn- push-button [button-id task]
  {:name "push-button"
   :arguments {:button-id button-id :task task} ;:priority "high"}
   :world nil
   :source nil
   :type "action"})


(defn request-action
  "Creates an action request."
  [action arguments]
  {:name action
   :arguments arguments
   :world nil
   :source nil
   :type "action"})

(defn configure-object-lost-detector
  [descriptor]
  {:name "update-lost-detector"
   :arguments {:descriptor descriptor}
   :type "automation"
   :world nil
   :source nil})

(defn wait-task []
  (let [wait-response [(tasks/sr-link [] (push-button \← :drive))
                       (tasks/sr-link [(d/descriptor :type "object")] (push-button \← :drive))
                       (tasks/initial-response (push-button \← :drive))]
        wait-strategy [(att/partial-strategy task-switch 11)
                       (att/partial-strategy actions 10)
                       (att/partial-strategy remember-cop 1.5)
                       (att/partial-strategy gaze 9)
                       (att/partial-strategy memory 7)
                       (att/partial-strategy saccade 5)
                       (att/partial-strategy fixation-gaze 6)
                       (att/partial-strategy button-strat 2)
                       (att/partial-strategy default-strat 1 0.1)]]
    (tasks/task "wait"
          wait-strategy
          wait-response
          (d/extend-descriptor cop-descriptor :tracked? false))))

(defn driving-task []
  (let [drive-response [(tasks/sr-link [] (push-button \← :drive))
                        (tasks/initial-response (push-button \← :drive))]
        drive-strategy [(att/partial-strategy task-switch 11)
                        (att/partial-strategy actions 10)
                        (att/partial-strategy gaze 9)
                        (att/partial-strategy memory 7)
                        (att/partial-strategy object 6)
                        (att/partial-strategy saccade 5)
                        (att/partial-strategy new-color-fixations 4)
                        (att/partial-strategy uncle-fixations 3)
                        (att/partial-strategy button-strat 2 0.5)
                        (att/partial-strategy default-strat 1 0.1)]]
    ;; the driving task is never complete in this model.
    (tasks/task "drive"
          drive-strategy
          drive-response
          ;; ensure that the task never completes
          (d/descriptor :name false))))

(defn drive-to-target [self target content]
  (let [self-y (-> self (obj/get-region content) reg/center :y)
        target-y (-> target (obj/get-region content) reg/center :y)
        ;; room for error
        buffer 10]
    (cond
      (< (math/abs (- self-y target-y)) buffer) (push-button \← :drive)
      (< self-y target-y) (push-button \↓ :drive)
      (> self-y target-y) (push-button \↑ :drive))))

(def kill-target-strategy
  [(att/partial-strategy task-switch 11)
   (att/partial-strategy actions 10)
   (att/partial-strategy memory 7)
   (att/partial-strategy object 6)
   (att/partial-strategy saccade 5)
   (att/partial-strategy fixation-gaze 6)
   (att/partial-strategy new-color-fixations 4)
   (att/partial-strategy self-fixations 1 0.1)
   (att/partial-strategy cop-fixations 4)
   (att/partial-strategy button-strat 2 1)
   (att/partial-strategy default-strat 1 0.1)])


;; NOTE
;; kill-witness and kill-uncle end when the objects that they are associated with
;; are no longer traciked. this is handled by using an object-lost-detector that
;; reports when an object either is no longer around in memory or is around but
;; no longer tracked. the descriptor used for the completion condition checks
;; to ensure that the object lost report keyed off the same descriptor that the
;; task is targeting.
(defn kill-witness-task [& {:keys [strategy-hash sr-links]}]
  (let [kill-witness-response [(tasks/sr-link [] (push-button \← :drive))
                               (tasks/sr-link [(d/descriptor :color "black")] (push-button \↑ :drive))
                               (tasks/sr-link [self-descriptor witness-descriptor] drive-to-target)
                               (tasks/initial-response (configure-object-lost-detector witness-descriptor))
                               (tasks/initial-response (push-button \← :drive))]]
       ;; doesn't seem to be a completion condition for this.
       ;; not sure why. will need to fix that later.
       (tasks/task "kill-witness"
             kill-target-strategy
             kill-witness-response
             (d/descriptor :name "object-lost" :type "instance" :descriptor witness-descriptor))))

(defn kill-uncle-task []
  (let [kill-uncle-response [(tasks/sr-link [] (push-button \← :drive))
                             (tasks/sr-link [(d/extend-descriptor self-descriptor :tracked? false)] (fn [self content] (when (d/none-match (d/extend-descriptor self-descriptor :tracked? true) content) (push-button \↓ :drive))))
                             (tasks/sr-link [(d/extend-descriptor self-descriptor :tracked? true) uncle-descriptor] drive-to-target)
                             (tasks/initial-response (configure-object-lost-detector uncle-descriptor))
                             (tasks/initial-response (push-button \← :drive))]]
       (tasks/task "kill-my-uncle"
             kill-target-strategy
             kill-uncle-response
             (d/descriptor :name "object-lost" :type "instance" :descriptor uncle-descriptor))))

;; NOTE
;; on representation:
;;    we store the library as a map between the task schema's handle and the
;;       schema itself.
(def task-library (tasks/build-task-library (wait-task) (driving-task) (kill-witness-task) (kill-uncle-task)))

;; NOTE
;; on representation:
;;    when defining nodes, we name the task instance, specify the handle of the task
;;       schema, and identify the task at the top of the hierarchy.
;;    we use the schema handle to avoid worrying about symbols and namespaces when
;;       working with the task library.
;; OR subtasks
;;    this is the normal pattern
;; AND subtasks
;;    no special representation
;;    the sequential execution is handled through SR links
;;    the completion condition for the first task in the conjunction has the completion
;;       condition for the entire set of subtasks, and it will get passed on to them
;;       because they will form a subtree of the first task.
;;    to move from one task to the next, the trigger of task 1 encoded in the edge
;;       indicates when it should move to the specified child task
(def drive-task-hierarchy
  {:top "drive"
   :nodes
   {"drive"        "drive"
    "kill-uncle"   "kill-my-uncle"
    "kill-witness" "kill-witness"
    "wait-uncle"   "wait"
    "wait-witness" "wait"}
   :edges
   [{:parent "drive" :trigger uncle-descriptor :child "kill-uncle"}
    {:parent "kill-uncle" :trigger (d/extend-descriptor witness-descriptor :tracked? true) :child "kill-witness"}
    {:parent "kill-uncle" :trigger (d/extend-descriptor cop-descriptor :tracked? true) :child "wait-uncle"}
    {:parent "kill-witness" :trigger (d/extend-descriptor cop-descriptor :tracked? true) :child "wait-witness"}]})

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn sensor-setup []
  (model/setup
    (model/add stable-viewpoint)
    (model/add touch)))

(def task-name
  (i# (let [task (d/first-element (:content %) :name "task")]
        (format "%s(%s)" (-> task :arguments :instance-name) (-> task :arguments :handle)))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn component-setup []
  (model/setup
   ;; visual pipeline components
   ;; HACK: the add-visual-pipeline-components function assumes you want the
   ;;       stable-viewpoint sensor, and doesn't seem to be able to accommodate
   ;;       overloading to optionally take a sensor. For now, we'll write it all out.
   (model/add display.status
              {:x 600 :y 120 :panel-height 250
               :elements
               (list ["Task" task-name])})
   (model/add image-segmenter {:sensor (get-sensor :stable-viewpoint)
                               :inner-contours? false
                               :min-saturation 0.1
                               ; :min-segment-length 50
                               ; :min-segment-area 2250
                               })
   (model/add object-file-binder)
   (model/add vstm {:diff-size-comparator size-comparator
                    :forget-untracked-first? true})
   (model/add object-locator {:sensor (get-sensor :stable-viewpoint)
                              :use-hats? false
                              :max-normed-distance 1})

   ;; gaze pipeline components
   ;; HACK: the add-gaze-pipeline-components function assumes you want the
   ;;       stable-viewpoint sensor, and doesn't seem to be able to accommodate
   ;;       overloading to optionally take a sensor. For now, we'll write it all out.
   (model/add gaze-updater {:sensor (get-sensor :stable-viewpoint)})
   (model/add saccade-requester {:sensor (get-sensor :stable-viewpoint)})
   (model/add smooth-pursuit {:sensor (get-sensor :stable-viewpoint)})
   (model/add highlighter.gaze-target)
   (model/add highlighter.maintenance)

   ; (model/add articulator {:audio true :duration-factor 1.25})
   ;(model/add attention-manager)
   ;(model/add button-display ({\↑ "↑" \↓ "↓" \← "←" \→ "→"} 0 600))
   (model/add button-pusher)
   (model/add color-change-detector)
   (model/add highlighter.color
              {:sensor (get-sensor :stable-viewpoint)
               :excluded-colors #{"black"}})
   (model/add highlighter.crowding {:crowding-dist 2.0})
   ;(model/add echo-chamber)
   (model/add element-preserver
              {:predicate #(or (and (= (:name %) "memory-update")
                                    (nil? (:task (:arguments %))))
                               (= (:name %) "memorize"))})
   (model/add episodic-memory {:descriptors object-descriptors})
    ;(model/add fixation-display ((get-sensor "stable-viewpoint") "Proto-Objects" 450 0
                                ;(list :crowding :subsegments :scan-trace) display-scale))
   (model/add display.fixations {:sensor (get-sensor :stable-viewpoint)
                                      :x 700 :y 600
                                      :image-scale display-scale})

  ;;  (model/add fixation-display
  ;;             {:sensor (get-sensor :stable-viewpoint)
  ;;              :display-name "Proto-Objects"
  ;;              :x 600
  ;;              :y 600
  ;;              :scale display-scale})

   (model/add display
              {:panels [[(i# (:image %))]]
               :element-type :image
               :image-scale display-scale
               :sensor (get-sensor :stable-viewpoint)
               :display-name "Video" :x 0 :y 600})
   (model/add motion-detector {:debug true :motion-lifespan 5})
   (model/add display.objects {:x 100 :y 100})
  ;;  (model/add object-display
  ;;             {:display-name "VSTM"
  ;;              :x 200
  ;;              :y 100
  ;;              :information (list ["color" #(-> % :arguments :color)]
  ;;                                 ["shape" #(-> % :arguments :shape)]
  ;;                                 ["size" #(-> % :arguments :size)])})
   (model/add object-lost-detector)
   (model/add highlighter.proximity); (25.0))
   (model/add relation.detector)
   (model/add relation.memorizer)
   (model/add relation.tracker {:object-descriptors object-descriptors})
   (model/add relation.update-detector)
   (model/add scanner {:sensor (get-sensor :stable-viewpoint)})
                       ;:radius-growth 0.002))
   (model/add reporter.shape)
   (model/add sr-link-processor)
   (model/add initial-response-generator)
   (model/add task-hierarchy {:hierarchy drive-task-hierarchy})
   (model/add task-ltm {:tasks task-library})
   (model/add touch-detector {:sensor (get-sensor :touch)})
   (model/add highlighter.vstm)
   (model/add working-memory)))

;(.registerFont (GraphicsEnvironment/getLocalGraphicsEnvironment)
;               (Font/createFont Font/TRUETYPE_FONT (File. ".ttf")))));

(def ball-spec1 (jbox2d/ball-spec SELF-LABEL Color/BLUE
                                 (* (:width env-args) 9/10)
                                 (* (:height env-args) 3/8)
                                 0 0
                                 :user-controlled? true))
                                 ;:y-jitter 2.0))

(def uncle-spec1 (jbox2d/ball-spec "U" Color/YELLOW 75 (+ (:height env-args) 50) 0 0
                                  :actions [(jbox2d/->Impulse 0 -100 1.0)
                                            (jbox2d/->Impulse 0 100 2.5)]))

(def cop-spec1 (jbox2d/ball-spec "COP" Color/RED 225 75 0 0
                                :blink (Color. 255 100 100)
                                :actions [(jbox2d/->Impulse 0 -100 4.0)]))

(def specs1
  (if COP? [ball-spec1 uncle-spec1 cop-spec1]
    [ball-spec1 uncle-spec1]))


(def ball-spec2 (jbox2d/ball-spec SELF-LABEL Color/BLUE
                                 (* (:width env-args) 9/10)
                                 (* (:height env-args) 3/8)
                                 0 0
                                 :user-controlled? true))
                                 ;:y-jitter 2.0))

(def uncle-spec2 (jbox2d/ball-spec "U" Color/YELLOW 75 (+ (:height env-args) 50) 0 0
                                  :actions [(jbox2d/->Impulse 0 -100 1.0)
                                            (jbox2d/->Impulse 0 100 2.5)]))

(def witness-spec2 (jbox2d/ball-spec "W" Color/GREEN 600 75 0 0))

(def specs2
  (if COP? [ball-spec2 uncle-spec2 witness-spec2]
    [ball-spec2 uncle-spec2]))


(def ball-spec3 (jbox2d/ball-spec SELF-LABEL Color/BLUE
                                 (* (:width env-args) 9/10)
                                 (* (:height env-args) 3/8)
                                 0 0
                                 :user-controlled? true))
                                 ;:y-jitter 2.0))

(def uncle-spec3 (jbox2d/ball-spec "U" Color/YELLOW 75 (+ (:height env-args) 50) 0 0
                                  :actions [(jbox2d/->Impulse 0 -100 1.0)
                                            (jbox2d/->Impulse 0 100 2.5)]))

(def cop-spec3 (jbox2d/ball-spec "COP" Color/RED 225 -50 0 0
                                :blink (Color. 255 100 100)
                                :actions [(jbox2d/->Impulse 0 100 4.0)
                                          (jbox2d/->Impulse 0 -100 5.5)
                                          (jbox2d/->Impulse 0 -100 7.0)
                                          (jbox2d/->Impulse 0 100 8.5)]))

(def specs3
  (if COP? [ball-spec3 uncle-spec3 cop-spec3]
    [ball-spec3 uncle-spec3]))


(def ball-spec4 (jbox2d/ball-spec SELF-LABEL Color/BLUE
                                 (* (:width env-args) 9/10)
                                 (* (:height env-args) 3/8)
                                 0 0
                                 :user-controlled? true))
                                 ;:y-jitter 2.0))

(def uncle-spec4 (jbox2d/ball-spec "U" Color/YELLOW 75 (+ (:height env-args) 50) 0 0
                                  :actions [(jbox2d/->Impulse 0 -100 0.5)
                                            (jbox2d/->Impulse 0 100 2)]))

(def cop-spec4 (jbox2d/ball-spec "COP" Color/RED 225 75 0 0
                                :blink (Color. 255 100 100)
                                :actions [(jbox2d/->Impulse 0 -100 2)
                                          (jbox2d/->Impulse 0 100 3.5)
                                          (jbox2d/->Impulse 0 100 10.5)
                                          (jbox2d/->Impulse 0 -100 12)
                                          (jbox2d/->Impulse 0 -100 13.5)
                                          (jbox2d/->Impulse 0 100 15)]))

(def witness-spec4 (jbox2d/ball-spec "W" Color/GREEN 600 75 0 0))

(def specs4
  (if COP? [ball-spec4 uncle-spec4 cop-spec4 witness-spec4]
    [ball-spec4 uncle-spec4]))


;"specs1 has stationary cop that leaves, specs2 has stationary witness that persists, specs3 has cop that enters then leaves, spec4 has cop that follows then leaves"
(def specs
   [specs1 specs2 specs3 specs4])

(defn run
  ([]
   (run 900 0 false))
  ([cycles]
   (run cycles 0 false))
  ([cycles spec]
   (run cycles spec false))
  ([cycles spec record?]
   ((resolve 'arcadia.core/startup)
    'arcadia.models.driving-2d-cop
    'arcadia.simulator.environment.intersection
    (assoc env-args :specs (nth specs spec))
    cycles
    :record? record?)))

(defn debug
  [cycles]
  ((resolve 'arcadia.core/debug)
   'arcadia.models.driving-2d-cop
   'arcadia.simulator.environment.intersection
   (assoc env-args :specs (nth specs 0))
   cycles))

(defn play
  [cycles]
  (g/apply-map jbox2d/run-environment
               'arcadia.simulator.environment.intersection
               cycles (assoc env-args :specs (nth specs 0) :x 100)))

;(run 500)
