(ns arcadia.models.stroop-soa
  "This is a model of the stroop effect."
  (:require [arcadia.utility.attention-strategy :as att]
            [arcadia.utility.display :refer [i#]]
            [arcadia.utility.model :as model]
            [arcadia.utility.objects :as obj]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.tasks :as t]
            [arcadia.models.core]
            [arcadia.architecture.registry :refer [get-sensor]]))

(def stroop-size-similarity-threshold 0.8)
(defn- size-comparator [obj1 obj2]
  (obj/similar-size-contours? obj1 obj2 :threshold  stroop-size-similarity-threshold))

(defn- select-focus-speech
  "Select a focus of attention that prefers speech actions."
  [expected]
  (or (d/rand-element expected :type "action" :name "vocalize")
      (d/rand-element expected :type "action" :name "subvocalize")))

(defn- task-switch [expected]
  (d/rand-element expected :name "switch-task" :type "action"))

(defn- say-word [word]
  {:name "speak"
   :arguments {:word word
               :effector :speech}
   :world nil
   :type "environmental-action"})

(defn- enhance-color [strength]
  {:name "update-color-semantics"
   :arguments {:enhancement strength}
   :world nil
   :type "automation"})

(defn- enhance-word [strength]
  {:name "update-word-semantics"
   :arguments {:enhancement strength}
   :world nil
   :type "automation"})

(defn- enhance-response [task-type]
  {:name "update-stroop-response"
   :arguments {:task task-type}
   :world nil
   :type "automation"})

(defn color-naming-task []
  (let [color-strategy [(att/partial-strategy task-switch 5)
                        (att/partial-strategy select-focus-speech 4)
                        (att/partial-strategy att/default-strategy 1 0.1)]
        ;; the initial model will have a component issues a subvocalize command
        ;; that subvocalize command will be transformed into a speech action
        color-response [;(t/sr-link [(d/descriptor :name "vocalize")]
                        ;           (fn [subv content]
                        ;             (say-word (-> subv :arguments :lexeme))))
                        (t/initial-response (enhance-response :color))
                        (t/initial-response (enhance-color 0.4))]]
    (t/task "color-naming"
            color-strategy
            color-response
            ;; never completes
            (d/descriptor :name false))))

(defn word-reading-task []
  (let [word-strategy [(att/partial-strategy task-switch 5)
                       (att/partial-strategy select-focus-speech 4)
                       (att/partial-strategy att/default-strategy 1 0.1)]
        ;; the initial model will have a component issues a subvocalize command
        ;; that subvocalize command will be transformed into a speech action
        word-response [;(t/sr-link [(d/descriptor :name "vocalize")]
                        ;           (fn [subv content]
                        ;             (say-word (-> subv :arguments :lexeme))))
                       (t/initial-response (enhance-response :word))
                       (t/initial-response (enhance-word 0.4))]]
    (t/task "word-reading"
            word-strategy
            word-response
            ;; never completes
            (d/descriptor :name false))))

(def stroop-task-hierarchy
  {:top "color-naming"
   :nodes
   {"color-naming"        "color-naming"
    "word-reading"        "word-reading"}})

(def task-library (t/build-task-library (color-naming-task) (word-reading-task)))

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
   (model/add highlighter.color {:sensor (get-sensor :stable-viewpoint) :segment-type "text-segmentation"})
   (model/add object-file-binder)
   (model/add vstm {:diff-size-comparator size-comparator})
   (model/add object-locator {:sensor (get-sensor :stable-viewpoint)
                              :use-hats? false})
   (model/add display.objects {:x 325 :y 175
                               :rows 1 :cols 1})
                            ;;    :elements [["text" (i# (some-> % :panel :arguments :text))]]})
   (model/add display
              {:display-name "Input"
               :cols 1 :rows 1
               :image-width 256 :image-height 256
               :element-type :image :center? true
               :x 0
               :y 0
               :elements
               [[(i# (-> %
                         :content
                         (d/first-element :name "text-segmentation")
                         :arguments
                         :image))]]}
              :input-display)
  ;;  (model/add display
  ;;             {:display-name "Subvocalization"
  ;;              :x 500 :y 200
  ;;              :panel-width 200
  ;;              :panel-height 25
  ;;              :elements
  ;;              [["Text"
  ;;                (i# (when (-> % :focus (d/element-matches? :name "subvocalize" :type "action"))
  ;;                      (println :LEXEME (-> % :focus :arguments :lexeme))
  ;;                      (-> % :focus :arguments :lexeme)))]]}
  ;;             :subvocalization-display)
   (model/add display.environment-messages
              {:display-name "Subvocalization"
               :x 325 :y 0
               :panel-width 200
               :bold? false})
   (model/add new-object-recorder)
   (model/add sr-link-processor)
   (model/add initial-response-generator)
   (model/add task-hierarchy {:hierarchy stroop-task-hierarchy})
   (model/add task-ltm {:tasks task-library})
   (model/add working-memory)
   (model/add wm-handle-refresher)
   (model/add display.status
              {:display-name "Status" :panel-width 400 :panel-height 150
               :caption-width 110 :precision 2
               :x 0 :y 400
               :elements
               [["Task" task-name]]})
   #_(model/add display.scratchpad
              {:x 100 :y 600
               :panel-width 400
               :panel-height 150})



   ;(model/add display.scratchpad {:x 800 :y 0 :sensor (get-sensor :stable-viewpoint)})

   ;; Stroop specific components
   ;; the parameters for semantics.word and semantics.color are set based on the threshold in response-stm
   (model/add semantics.word {:base-strength 0.6}) ;0.6
   (model/add semantics.color {:base-strength 0.4}) ;0.4
   (model/add response-stm {:threshold 13}) ;13 for SOA, 11 for gratton 
   (model/add stroop-articulator)
   (model/add stroop-control)
   
   (model/add stroop-segmenter {:sensor (get-sensor :stable-viewpoint)})
   (model/add stroop-trial-starter)
   (model/add button-pusher)))
   ;-0(model/add vocalizer)


(defn example-run
  [trials & {:keys [max-cycles output-file]}]
   ;; use resolve to avoid circular dependencies for the example run
  ((resolve 'arcadia.core/startup)
   'arcadia.models.stroop-soa
   'arcadia.simulator.environment.stroop
   {:n-trials trials
    :task (keyword (:top stroop-task-hierarchy))
    :weights {:incongruent 1 :neutral 1};:congruent 3 :incongruent 4 :neutral 3}
    :color-onset 0
    :stimulus-interval 1000
    :intertrial-interval 100}
   max-cycles
   :save-data? output-file
   :record? false
   :data-parameters {:output-file output-file}))

;; to change task types,
;; 1. edit the top node of the stroop-task-hierarchy to be "word-reading" or "color-naming"

(defn run-experiment
  [trials & {:keys [output-file recording-file]}]
   ;; use resolve to avoid circular dependencies for the example run
  ((resolve 'arcadia.experiments/startup-experiment)
   'arcadia.models.stroop-soa
   'arcadia.simulator.environment.stroop
   {:n-trials trials
    :task (keyword (:top stroop-task-hierarchy))
    :stimulus-interval 1000
    :intertrial-interval 100}

   :record? recording-file
   :save-data? output-file
   :data-parameters {:output-file output-file}
   :env-parameter-ranges [[:weights [{:neutral 1 :incongruent 1 :congruent 1}]]
                          [:color-onset [-400 -300 -200 -100 0 100 200 300 400]]]))

;; For the gratton effect, we will make the following adjustments
;; 1. incongruent trials will
;;    a. increase the threshold to 13 of the next trial
;;    b. reduce the effect of word naming by half (base strength to 0.3)
;; 2. congruent trials will
;;    a. reset the threshold to 11 for the next trial
;;    b. reset the effect of word naming (base strength to 0.6)
;;
;; incongruent trials result in conflict at the accumulation layer
;; which will produce an adjustment in time for the next trial

;; For SOA results, the threshold should be set to 13 and the
;; Stroop control component should be disabled. Recall that in
;; the Glaser & Glaser study, subjects were asked to signal
;; trial readiness by pressing a button. This could potentially
;; remove between-trial effects. The question of why the threshold
;; should be increased is left unanswered, but it could be that
;; part of preparation involves expecting a conflicting stimulus.
