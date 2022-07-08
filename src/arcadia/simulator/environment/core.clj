(ns
  ^{:doc "Defines a protocol for implementing simulation environments."}
  arcadia.simulator.environment.core)

;; the interface for developing environments draws from
;; OpenAI Gym. https://gym.openai.com

(defprotocol Environment
  (info [env])
  (step [env actions]
   "Takes one step through the environment, given a list of environmental actions
    performed by the model on the previous cycle. Returns a hash-map which may
    include the following keys:
    :observation
    :reward
    :done?       Has this environment reached its conclusion?
    :diagnostic-info
    :data        A hashmap of data that can be saved to a datafile describing a
                 model's performance.
    :message     A string that can be displayed to the display.environment-messages
                 component if one exists.
    :increment-message-count?
                 If this is true when a :message is provided, then increment
                 the number that appears to the left of the message in the display.
                 If this is false, then continue using the previously used number.
                 Note that this must be true at least once for a number to appear
                 at all (the starting number is 0, which will not be displayed).")
  (reset [env])
  (render [env mode close])
  (close [env])
  (seed [env seed-value]))

;;;;;;;;;;;;;;;;;
;; Action in a simulated environment
;;
;; there may be simultaneous actions
;; arcadia will pass a list of action commands to the environment
;; these action commands will be in a form that the environment can process
;;
;; from the arcadia side, there will be special elements with type
;; "environment-action" that will have as an argument :action-command.
;; these will appear in accessible content and will not receive focus.
;;
;; a component will generate an element of type "action" and must know
;; what sort of action commands can be given to the environment.
;;
;; a general component will convert these actions to environment-actions
;; if they receive focus. this component will be parameterizable so that
;; it can be told to look for actions of a particular type and to delay
;; the appearance of the associated environment-action by one or more
;; cycles. there should be a 1-to-1 mapping between the number of
;; possible environment-actions and the number of instances of this
;; component. this component will need to be redesigned once complex
;; actions (not simple actions that can be modeled as effectively
;; instantaneous and single-stage) can be carried out.




;; The function comments below are taken liberally from OpenAI Gym and
;; (lightly) adapted under terms of that software's use according to The MIT
;; License. The information is copied here for convenience.
;;
;; Adaptation and use of the basic API is under development for other simulation
;; environments and reference should be made to those for implementation
;; examples.

;;; info
;; Returns general, stable properties of the environment. These can
;; include information about the environment's commands (e.g., the
;; set of actions available and the set of render modes) and
;; information about the environment's "world" (e.g., dimensions,
;; frame size).

;;; step
;;
;; Run one timestep of the environment's dynamics. When end of
;; episode is reached, you are responsible for calling `reset()`
;; to reset this environment's state.
;;
;; Accepts an action and returns a tuple (observation, done).
;;
;; Args:
;; action (object): an action provided by the environment
;;
;; Returns:
;; observation (object): agent's observation of the current environment
;; done (boolean): whether the episode has ended, in which case further step()
;;                 calls will return undefined results

;;; reset
;;
;; Resets the state of the environment and returns an initial observation.
;;
;; Returns: observation (object): the initial observation of the space.)

;;; render
;;
;; Renders the environment.
;;
;; The set of supported modes varies per environment. (And some
;; environments do not support rendering at all.) By convention,
;; if mode is:
;;
;; - human: render to the current display or terminal and
;;          return nothing. Usually for human consumption.
;; - rgb_array: Return a BufferedImage having type BufferedImage/TYPE_INT_BGR
;;
;; Args:
;; mode (str): the mode to render with
;; close (bool): close all open renderings

;;; close
;;
;; Perform any necessary cleanup.

;;; configure
;;
;; Provides runtime configuration to the environment.
;;
;; This configuration should consist of data that tells your
;; environment how to run (such as an address of a remote server,
;; or path to your ImageNet data). It should not affect the
;; semantics of the environment.

;;; seed
;;
;; Sets the seed for this env's random number generator(s).
