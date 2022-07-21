(ns arcadia.models.core
  (:require [arcadia.architecture.registry :refer [get-sensor]]
            clojure.tools.namespace.repl)

  #_{:clj-kondo/ignore [:unused-namespace]}
  (:require [arcadia.utility.model :as model])
  (:require
   (arcadia.sensor action-sensor minigrid-sensor stable-viewpoint text-sensor)
   (arcadia.component.highlighter basic-shape center color crowding gaze-target maintenance
                                  proximity saliency vstm)
   (arcadia.component.minigrid action-detector actor egocentric-map enter-room-detector episode-binder highlighter
                               key-query
                               mapper object-navigator pathfinder perception segmenter test-query wallfollower)
   (arcadia.component.reporter heading-change letter number-magnitude number-parity
                               numerosity object-height object-width shape)
   (arcadia.component.relation detector memorizer tracker update-detector)
   (arcadia.component.semantics color word)
   (arcadia.component
    action-detector
    allocentric-layout
    automatic-number-sensor
    bottom-up-saliency
    button-pusher
    color-change-detector
    comparison-recorder
    conceptual-ltm
    contact-detector
    covert-return-inhibitor
    display
    egocentric-layout
    element-preserver
    episode-binder
    episodic-ltm
    episodic-memory
    feedback
    gaze-updater
    image-segmenter
    initial-response-generator
    java-memory-manager
    length-comparator
    motion-detector
    motion-planner
    new-object-guess-recorder
    new-object-recorder
    number-identifier
    numerosity-lexicalizer
    object-count-subvocalizer
    object-file-binder
    object-locator
    object-lost-detector
    ocr-segmenter
    parameter-configurations
    phonological-buffer
    random-refresher
    recall-initializer
    recall-linker
    refresh-inhibitor
    response-stm
    response-stm-ctl
    saccade-requester
    scanner
    scene-constructor
    smooth-pursuit
    spatial-ltm
    speed-change-detector
    sr-link-processor
    stroop-articulator
    stroop-control
    stroop-control-new
    stroop-segmenter
    stroop-trial-starter
    sweep-inhibitor
    sweep-updater
    task-hierarchy
    task-ltm
    trial-responder
    trial-starter
    two-button-display
    vocalizer
    vstm
    vstm-enumerator
    vstm-enumerator-simple
    vstm-inhibitor
    wm-display
    wm-handle-refresher
    working-memory)))

;;Add an alias for each component and sensor to the namespace
(doseq [ns0 (-> clojure.tools.namespace.repl/refresh-tracker
                :clojure.tools.namespace.track/deps :dependencies
                (get 'arcadia.models.core))]
  (when-let [alias0
             (or (->> ns0 str (re-matches #"arcadia\.component\.(.*)") second)
                 (->> ns0 str (re-matches #"arcadia\.sensor\.(.*)") second))]
    (alias (symbol alias0) ns0)))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn add-visual-pipeline-components
  "Setup instructions for the components that run the general visual pipeline."
  []
  (model/setup
    (model/add image-segmenter {:sensor (get-sensor :stable-viewpoint)})
    (model/add object-file-binder)
    (model/add vstm)
    (model/add object-locator {:sensor (get-sensor :stable-viewpoint)
                               :use-hats? false})))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn add-gaze-pipeline-components
  "Setup instructions for components that handle gaze."
  []
  (model/setup
    (model/add gaze-updater {:sensor (get-sensor :stable-viewpoint)})
    (model/add saccade-requester {:sensor (get-sensor :stable-viewpoint)})
    (model/add smooth-pursuit {:sensor (get-sensor :stable-viewpoint)})
    (model/add highlighter.gaze-target)
    (model/add highlighter.maintenance)))
