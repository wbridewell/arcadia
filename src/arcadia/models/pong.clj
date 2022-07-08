(ns arcadia.models.pong
  (:require (arcadia.utility [model :as model]
                             [descriptors :as d]
                             [tasks :as t]
                             [relations :as r]
                             [attention-strategy :as att]
                             [objects :as obj]
                             [general :as gen]
                             [scan :as scan]
                             [display :refer [i#]])
            [arcadia.vision.regions :as reg]
            arcadia.models.core
            [arcadia.simulator.environment [jbox2d :as jbox2d]]
                                         ;  [pong :as env]]
            [arcadia.architecture.registry :refer [get-sensor]])
  (:import java.awt.Color))

;; Environment Parameters
(def env-args
  (assoc jbox2d/default-parameters
         :width 800
         :height 600
         :wall-outline-weight 1.5
         :scale 1.25))

(def display-scale 0.65)

(def scan-speeds [150 100 50])
(def scan-speed->radius 0.0125)
(def min-delta-count 4)
(def enters-ub 0.95)
(def enters-lb 0.05)

(def ball-descriptor (d/object-descriptor "the ball" :shape-description "circle"
                                          :color "purple" :brightness "light"))

(def wall-descriptor (d/object-descriptor "a wall" :shape-description "rectangle"))
(def paddle-descriptor (d/extend-object-descriptor wall-descriptor "the paddle" :color "white"))
(def goal-descriptor (d/extend-object-descriptor wall-descriptor "a goal" :color #(and (not= % "black")
                                                                                       (not= % "white"))))
(def top-goal-descriptor (d/extend-object-descriptor wall-descriptor "the top goal" :color "red"))
(def middle-goal-descriptor (d/extend-object-descriptor wall-descriptor "the middle goal" :color "green"))
(def bottom-goal-descriptor (d/extend-object-descriptor wall-descriptor "the bottom goal" :color "blue"))

(def object-descriptors [ball-descriptor paddle-descriptor top-goal-descriptor
                         middle-goal-descriptor bottom-goal-descriptor
                         goal-descriptor wall-descriptor])

(defn get-fixations-for-descriptor
  "This function returns all fixation requests to a region shared by an
  object which matches the provided object-descriptor."
  ([content object-descriptor]
   (get-fixations-for-descriptor content object-descriptor
                                 (obj/get-vstm-objects content :tracked? true)))
  ([content object-descriptor vstm]
   (mapcat #(att/get-fixations-to-region content (obj/get-region % content))
           (d/filter-matches object-descriptor vstm))))

;;----------------------------------VSTM parameters-----------------------------
;; Used by VSTM to compare sizes of objects
(def pong-size-similarity-threshold 0.1)
(defn- size-comparator [obj1 obj2]
  (obj/similar-size? (-> obj1 :arguments :region)
                     (-> obj2 :arguments :region)
                     pong-size-similarity-threshold))

(def pong-loc-similarity-threshold 0.01)
(defn- loc-comparator [obj1 obj2]
  (and (obj/within-epsilon? (-> obj1 :arguments :region reg/center :x)
                            (-> obj2 :arguments :region reg/center :x) pong-loc-similarity-threshold)
       (obj/within-epsilon? (-> obj1 :arguments :region reg/center :y)
                            (-> obj2 :arguments :region reg/center :y) pong-loc-similarity-threshold)))

;;-----------------------------------Enters Definition--------------------------
(declare enters-relation)

(defn enters
  "Given a contact episode, determines whether the enters relation holds.
  In order for this to be true, the ball must be contacting the goal
  enough to be inside of its region."
  [contact motion content]
  (let [goal (d/first-match goal-descriptor (-> contact :arguments :objects))
        ball (d/first-match ball-descriptor (-> contact :arguments :objects))]
    (when (and goal ball
               (d/descriptor-matches? ball-descriptor (-> motion :arguments :objects first))
               (some-> contact :arguments :distance zero?))
      (r/instantiate enters-relation [ball goal] [contact]))))

(def enters-relation
  "Enters is a unique, ordered relation of arity 2, which is true when
  an object contacts the goal."
  (r/relation "enters" 2 true true  ;; arity of 2, arguments are unique and ordered
              (t/sr-link [(d/descriptor :name "event-stream" :world "episodic-memory"
                                        :event-name "contact")
                          (d/descriptor :name "event-stream" :world "episodic-memory"
                                        :event-name "motion" :x-dir -1)]
                         enters)
              (t/sr-link [(d/extend-descriptor ball-descriptor :precise-x-dir 1) goal-descriptor]
                         (fn [ball goal content]
                           (r/instantiate enters-relation [ball goal] [false] [ball])))))

(def pong-relations [enters-relation])

;; ---- Attentional Strategy Functions ----

(defn- gaze-st [expected] (d/rand-element expected :name "gaze" :saccading? true))
(defn- task-switch-st [expected] (d/rand-element expected :name "switch-task" :type "action"))
(defn- scan-on-st [expected] (d/rand-element expected :name "scan" :type "action" :ongoing? true))
(defn- memory-st [task expected] (d/rand-element expected
                                                 :name #(or (= % "memorize") (= % "memory-update"))
                                                 :type "action" :task #(or (nil? %) (= % task))))
(defn- object-st [expected] (d/rand-element expected :name "object" :type "instance" :world nil))
(defn- scan-int-st [expected] (d/rand-element expected :name "scan-intersection"))
(defn- saccade-st [expected] (d/rand-element expected :name "saccade"))
(defn- subvocalize-st [task expected] (d/rand-element expected :name "subvocalize" :type "action" :task task))

(defn- scan-st [expected] (d/rand-element expected :name "scan" :type "action"))
(defn- action-st [task expected] (d/rand-element expected :type "action" :task task))
(defn- gfix-st [expected] (d/rand-element expected :name "fixation" :reason "gaze"))
(defn- new-segs-st
  "Look at new segments that are not in vstm yet."
  [expected]
  (d/rand-element (att/remove-object-fixations expected expected) :name "fixation" :reason "color"))

(defn- task-segs-st
  "Look at task-relevant segments."
  [descriptor expected]
  (let [dfs (get-fixations-for-descriptor expected descriptor)]
    (or
      (d/rand-element dfs :reason "maintenance")
      (d/rand-element dfs :reason "color"))))

(defn- fix-segs-st [expected] (d/rand-element expected :name "fixation"))
(defn- any-st [expected] (gen/rand-if-any (seq expected)))

(defn- make-pong-strategy [task descriptor]
  [(att/partial-strategy gaze-st 15)
   (att/partial-strategy task-switch-st 14)
   (att/partial-strategy scan-on-st 13)
   (att/partial-strategy (partial memory-st task) 12)
   (att/partial-strategy object-st 11)
   (att/partial-strategy scan-int-st 10)
   (att/partial-strategy saccade-st 9)
   (att/partial-strategy (partial subvocalize-st task) 8)
   (att/partial-strategy scan-st 7)
   (att/partial-strategy (partial action-st task) 6)
   (att/partial-strategy gfix-st 5)
   (att/partial-strategy new-segs-st 4)
   (att/partial-strategy (partial task-segs-st descriptor) 3)
   (att/partial-strategy fix-segs-st 2)
   (att/partial-strategy any-st 1)])

;;------------------------ Pong Tasks -----------------------------
(defn tracking-task []
  (let [relations [(t/sr-link [(d/extend-descriptor ball-descriptor :precise-x-dir -1 :precise-delta-x some? :precise-delta-y some?
                                                    :precise-delta-count #(and (some? %) (> % min-delta-count)))]
                              (fn [ball content]
                                (scan/relation-scan ball nil content :relation enters-relation
                                                    :start-descriptor ball-descriptor
                                                    :context (scan/scan-context ball-descriptor)
                                                    :scan-speeds scan-speeds :scan-speed->radius scan-speed->radius)))
                   (t/sr-link [(d/descriptor :name "scan-intersection"
                                             :relation enters-relation
                                             :start (partial d/descriptor-matches? ball-descriptor)
                                             :target (partial d/descriptor-matches? goal-descriptor))]
                              (gen/partial-kw scan/scan-intersection->relation :upper-bound enters-ub :lower-bound enters-lb))]
        responses [(t/sr-link [(r/instance-descriptor enters-relation :arguments [ball-descriptor goal-descriptor]
                                                      :value true :context #(not= % "real") :world "working-memory")]
                              (fn [enters content]
                                (when (scan/done-scanning? content :instance enters :scan-speeds scan-speeds)
                                   (t/subvocalize "move" :track))))
                   (t/sr-link [(d/descriptor :world "working-memory")
                               (d/extend-descriptor ball-descriptor :precise-x-dir 1)]
                              (t/subvocalize "erase-memory" :track))]]

    (t/task "track"
            (make-pong-strategy :track ball-descriptor)
            (concat relations responses)
            ;; never completes
            (d/descriptor :name false))))

(defn- loc->key
  "Converts a goal label to a top/middle/bottom response key"
  [label]
  (case label
        "the top goal" \T
        "the bottom goal" \B
        "the middle goal" \M))

(defn moving-task []
  (t/task "move"
          (make-pong-strategy :move paddle-descriptor)
          [(t/sr-link [(d/extend-descriptor paddle-descriptor :precise-delta-count #(> % min-delta-count))
                       (r/instance-descriptor enters-relation :arguments [ball-descriptor goal-descriptor]
                                              :value true :context #(not= % "real") :world "working-memory")]
                      (fn [paddle enters content]
                        (let [goal (-> enters :arguments :arguments second)
                              button (-> enters :arguments :argument-descriptors second :label loc->key)]
                          (when (and (not (gen/near-equal? (-> paddle :arguments :region reg/center :y)
                                                           (-> goal :arguments :region reg/center :y)
                                                           100))
                                     (d/not-any-element content :name "action-feedback" :type "feedback" :action-command button))
                            {:name "push-button"
                             :arguments {:button-id button :task :move}
                             :type "action"}))))
           (t/sr-link [(d/descriptor :name "action-feedback" :type "feedback")]
                      (fn [feedback content]
                        (t/subvocalize "erase-memory" :move)))]
          ;; never completes
          (d/descriptor :name false)))

(defn erase-memory []
  (t/task "erase-memory"
          (make-pong-strategy :erase-memory paddle-descriptor)
          [(t/sr-link [(d/descriptor :name #(not= % "memory-equality") :world "working-memory")]
                      (fn [memory content]
                        {:name "memory-update"
                         :arguments {:old (list memory) :new (list)
                                     :task :erase-memory}
                         :type "action"
                         :world nil}))
           (t/sr-link []
                      (fn [content]
                        (when (d/not-any-element content :world "working-memory")
                          (t/subvocalize "track" :erase-memory))))]
          ;; never completes
          (d/descriptor :name false)))

;; all these tasks continue until they specifically transition into the next
;; task. there is no particular hierarchy in this version of the library
;; because it's a retrofit of an earlier model.
(def pong-tasks (t/build-task-library (tracking-task) (moving-task) (erase-memory)))

(def pong-task-hierarchy
  {:top "track"
   :nodes
   {"track"         "track"
    "move"          "move"
    "erase-memory-track"  "erase-memory"
    "erase-memory-move"  "erase-memory"}
   :edges
   [{:parent "track" :trigger (d/descriptor :name "subvocalize" :lexeme "move") :child "move"}
    {:parent "track" :trigger (d/descriptor :name "subvocalize" :lexeme "erase-memory") :child "erase-memory-track"}
    {:parent "move" :trigger (d/descriptor :name "subvocalize" :lexeme "erase-memory") :child "erase-memory-move"}]})

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn sensor-setup []
  (model/setup
   (model/add stable-viewpoint)
   (model/add action-sensor)))

(def task-name
  (i# (let [task (d/first-element (:content %) :name "task")]
        (format "%s(%s)" (-> task :arguments :instance-name) (-> task :arguments :handle)))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn component-setup []
  (model/setup
    (model/add image-segmenter {:sensor (get-sensor :stable-viewpoint)
                                :inner-contours? false
                                :min-segment-length 20
                                :min-segment-area 1000})
    (model/add object-file-binder)
    (model/add vstm {:diff-size-comparator size-comparator :diff-loc-comparator loc-comparator})
    (model/add object-locator {:sensor (get-sensor :stable-viewpoint)
                               :use-hats? false :max-normed-distance 3})

    (model/add gaze-updater {:sensor (get-sensor :stable-viewpoint)})
    (model/add saccade-requester {:sensor (get-sensor :stable-viewpoint)})
    (model/add smooth-pursuit {:sensor (get-sensor :stable-viewpoint)})
    (model/add highlighter.gaze-target)
    (model/add highlighter.maintenance)

    (model/add button-pusher)
    (model/add highlighter.color {:sensor (get-sensor :stable-viewpoint)})
    (model/add contact-detector {:contact-threshold 5.0})
    (model/add highlighter.crowding {:crowding-dist 2.0})
    (model/add element-preserver {:predicate
                                    #(or (and (= (:name %) "memory-update")
                                              (nil? (:task (:arguments %))))
                                         (= (:name %) "memorize"))})
    (model/add episodic-memory {:descriptors object-descriptors})
    (model/add feedback {:sensor (get-sensor :action-sensor)})
    (model/add display.fixations {:sensor (get-sensor :stable-viewpoint)
                                  :x 450 :y 0})
    (model/add reporter.heading-change)
    (model/add motion-detector {:debug true :motion-lifespan 5})
    (model/add display.objects {:x 0 :y 0})
    (model/add highlighter.proximity {:max-dist 25.0})
    (model/add relation.detector)
    (model/add relation.memorizer)
    (model/add relation.tracker {:object-descriptors object-descriptors})
    (model/add relation.update-detector)
    (model/add scanner {:sensor (get-sensor :stable-viewpoint)
                        :reflect-top (* (:scale env-args) (:wall-thickness env-args))
                        :reflect-bottom (* (:scale env-args)
                                           (- (:height env-args) (:wall-thickness env-args)))})
    (model/add reporter.shape)
    (model/add speed-change-detector)
    (model/add sr-link-processor)
    (model/add display
               {:display-name "Subvocalization"
                :x 0 :y 425
                :panel-width 200
                :panel-height 25
                :elements
                [["Text"
                  (i# (when (-> % :focus (d/element-matches? :name "subvocalize" :type "action"))
                        (-> % :focus :arguments :lexeme)))]]}
              :subvocalization-display)
    (model/add initial-response-generator)
    (model/add task-hierarchy {:hierarchy pong-task-hierarchy})
    (model/add task-ltm {:tasks pong-tasks})
    (model/add highlighter.vstm)
    (model/add working-memory)
    (model/add wm-display {:x 0 :y 600})
    (model/add display.status
               {:precision 2
                :x 1000 :y 120
                :elements [["Task" task-name]]})))


(def ball-spec (jbox2d/ball-spec "( ͡° ͜ʖ ͡°)" Color/MAGENTA 700 300
                                 -300 0 :a-vel 25.0
                                 :friction 0.05 :density 5.0))
                                 ;(normal -100 50) (normal 0 50)))
(def paddle-spec (jbox2d/wall-spec "paddle"
                                   (* (:wall-thickness env-args) 1.5) (* (:height env-args) 1/2)
                                   (:wall-thickness env-args) (/ (:height env-args) 3)
                                   :friction 1.0
                                   :color Color/WHITE
                                   :outline Color/BLACK
                                   :left-ctrl nil :right-ctrl nil
                                   :kinematic? true
                                   :user-controlled? true))

(defn run
  [cycles]
  ((resolve 'arcadia.core/startup)
   'arcadia.models.pong
   'arcadia.simulator.environment.pong
   (assoc env-args :specs [ball-spec paddle-spec])
   cycles))

(defn debug
  [cycles]
  ((resolve 'arcadia.core/debug)
   'arcadia.models.pong
   'arcadia.simulator.environment.pong
   (assoc env-args
          :specs [ball-spec paddle-spec])
   cycles))

(defn play
  [cycles]
  (gen/apply-map jbox2d/run-environment 'arcadia.simulator.environment.pong
                 cycles (assoc env-args
                               :specs [ball-spec paddle-spec]
                               :x 100)))
