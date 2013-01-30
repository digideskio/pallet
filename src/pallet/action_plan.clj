(ns pallet.action-plan
  "An action plan contains instances of actions (action maps) for execution.

   The action plan is built by executing a phase function. Each phase function
   calls actions which insert themselves into the action plan.

   The action plan is transformed to provide aggregated operations, run delayed
   crate functions, and to resolve precedence relations between actions.

   A translated plan is executed by passing an executor, which is a function
   that will be passed each action map in the plan.  The executor function is
   responsible for selecting the implementation of the action to be used, and
   calling the implementation with the action-map arguments.

   Note this is an implementation namespace."
  {:author "Hugo Duncan"}
  (:require
   [pallet.argument :as argument]
   [pallet.context :as context]
   [pallet.stevedore :as stevedore]
   [clojure.tools.logging :as logging]
   [clojure.string :as string])
  (:use
   [clojure.algo.monads :only [defmonad domonad m-seq m-map]]
   [clojure.set :only [union]]
   [clojure.string :only [trim]]
   [clojure.stacktrace :only [print-cause-trace]]
   [pallet.context :only [with-context in-phase-context-scope]]
   [pallet.core.session :only [session with-session]]
   [pallet.node-value :only [make-node-value set-node-value]]
   [pallet.session.action-plan
    :only [dissoc-action-plan get-session-action-plan]]
   pallet.action-impl))

;;; ## Action Plan Data Structure

;;; The action plan is a stack of action maps, where the action map could itself
;;; be a stack of action-maps (ie a tree of stacks). Branches are transformed
;;; into :blocks of the parent action map.

(defn push-block
  "Push a block onto the action-plan"
  [action-plan]
  (conj (or action-plan '(nil nil)) nil))

(defn pop-block
  "Take the last block and add it to the :blocks key of the scope below it in
  the stack.  The block is reversed to put it into the order in which elements
  were added."
  [action-plan]
  (let [block (peek action-plan)
        stack (pop action-plan)]
    (if-let [stem (next stack)]
      (conj
       stem
       (let [sb (first stack)]
         (conj
          (rest sb)
          (update-in (first sb) [:blocks]
                     (fn [blocks]
                       (conj (or blocks []) (reverse block)))))))
      (if-let [stem (seq (first stack))]
        (list
         (update-in
          stem :blocks (fn [blocks] (conj (or blocks []) (reverse block)))))
        (reverse block)))))

(defn- add-action-map
  "Add an action map to the action plan"
  [action-plan action-map]
  (let [action-plan (or action-plan '(nil nil))
        block (peek action-plan)
        stack (pop action-plan)]
    (conj stack (conj block action-map))))

;;; ## Action Instance Representation

;;; An instance of an action in the action plan is represented as a map,
;;; generated by `action-map`.

(def ^{:private true
       :no-doc true
       :doc "Set of executions that are delayed until translation time."}
  delayed-execution?
  #{:delayed-crate-fn :aggregated-crate-fn :collected-crate-fn})

(defn action-map
  "Return an action map for the given `action` and `args`. The action map is an
   instance of an action. The action plan is a tree of action maps.

   `options` specifies naming and dependencies for this instance of the action,
   and may contain :action-id, :always-before and :always-after keys. If an
   `options` is supplied, an action-id is generated if none present, to ensure
   that the standard action precedence is not altered. `:script-dir` and user
   options like `:sudo-user` may also be specified. `:script-prefix` can
   be used to prefix with something other than sudo.

   - :action          the action that is scheduled,
   - :args            the arguments to pass to the action function,
   - :context         captures the phase context, for use at execution time,
   - :action-id       a keyword that identifies the instance of an action,
   - :always-before   a symbol, keyword or set thereof. A symbol references an
                      action, and a keyword references an instance of an action,
   - :always-after    a symbol, keyword or set thereof,
   - :node-value-path a symbol specifying a node value (added when scheduled).

   The action is generated by the specified action function and arguments that
   will be applied to the function when the action plan is executed."
  [action arg-vector {:as options}]
  (let [options (and options (seq options)
                     (update-in options [:action-id]
                                #(or % (gensym "action-id"))))]

    (merge
     (merge (action-precedence action) options)
     {:action action
      :args arg-vector
      :context (if (delayed-execution? (action-execution action))
                 (drop-last (context/phase-context-scope))
                 (context/phase-contexts))})))

(defn action-map-execution
  "Helper to return the execution of the action associated with an action-map."
  [action-map]
  {:pre [(map? action-map) (:action action-map)]}
  (action-execution (:action action-map)))

;;; ## Schedule an action map in an action plan
(declare find-node-value-path)

(defn schedule-action-map
  "Registers an action map in the action plan for execution. This function is
   responsible for creating a node-value (as node-value-path's have to be unique
   for all instances of an aggregated action) as a handle to the value that will
   be returned when the action map is executed."
  [action-plan action-map]
  {:pre [(or (nil? action-plan)
             (instance? clojure.lang.IPersistentStack action-plan))]}
  (let [node-value-path (if (#{:aggregated :collected}
                             (action-map-execution action-map))
                          (or (find-node-value-path action-plan action-map)
                              (gensym "nv"))
                          (gensym "nv"))]
    [(make-node-value node-value-path)
     (add-action-map
      action-plan (assoc action-map :node-value-path node-value-path))]))

;;; ## Utilities
(defn multi-context-string
  "The string that is used to represent the phase context for :aggregated
  actions."
  {:no-doc true}
  [context]
  (when (seq context)
    (str "[" (string/join ": " context) "]")))

(defn context-string
  "The string that is used to represent the phase context for :in-sequence
  actions."
  {:no-doc true}
  [context]
  (when (seq context)
    (str (string/join ": " context) ": ")))

(defmulti context-label
  "Return a label for an action"
  (fn [action] (action-execution action)))

(defmethod context-label :default
  [{:keys [context] :as action}]
  (when-let [s (context-string context)]
    (trim s)))

(defmethod context-label :aggregated
  [{:keys [context] :as action}]
  (multi-context-string context))

(defmethod context-label :collected
  [{:keys [context] :as action}]
  (multi-context-string context))

(defn- action-plan?
  "Predicate for testing if action-plan is valid. An action-plan is either a
  sequence of action maps, or an action map."
  [action-plan]
  (if (and action-plan
           (or (sequential? action-plan)
               (map? action-plan)))
    true
    (logging/errorf "action-plan? failed: %s" action-plan)))

;;; ## Action Plan Transformation

;;; Transform functions for working with an action-plan containing action-maps..

(defn- walk-action-plan
  "Traverses an action-plan structure.  leaf-fn is applied to leaf
   action, list-fn to sequences of actions, and nested-fn to
   a nested scope. nested-fn takes the existing nested scope and a transformed
   arg list"
  [leaf-fn list-fn nested-fn action-plan]
  ;;{:pre [(action-plan? action-plan)]}
  (cond
    (sequential? action-plan) (list-fn
                               (map
                                #(walk-action-plan leaf-fn list-fn nested-fn %)
                                action-plan))
    (:blocks action-plan) (nested-fn
                           action-plan
                           (map
                            #(walk-action-plan leaf-fn list-fn nested-fn %)
                            (:blocks action-plan)))
    :else (leaf-fn action-plan)))

(defn- assoc-blocks
  [action blocks]
  (assoc action :blocks blocks))

;;; ## Transform Executions

;;; Takes the action plan, and reorganises it based on the action
;;; executions.

;;; The actions are sorted into :aggregated, :in-sequence, :collected order.

;;; Action maps for :aggregated and :collected actions are collapsed down to a
;;; single action map, with its :args as a sequence of all the :args from the
;;; individual action maps.

(defn- group-by-function
  "Transforms a seq of action-maps, generally some with identical action values
   into a sequence of actions where the :args are the concatenation of all of
   the :args of associated with each :impls in the original seq.  Sequence order
   from the original seq is retained. Keys over than :impls and :args are
   assumed identical for a given :impls value.

   e.g. (group-by-function
           [{:impls :a :args [1 2]}
            {:impls :b :args [3 4]}
            {:impls :a :args [5 6]}
            {:impls :c :args [7 8]]])
        => ({:impls :a :args ([1 2] [5 6])}
            {:impls :c :args ([7 8])}
            {:impls :b :args ([3 4])})"
  [action-plan]
  (letfn [(context-combine [result-action-map {:keys [context] :as action-map}]
            (if (string? (first context))
              (update-in result-action-map [:context]
                         conj (multi-context-string context))
              (update-in result-action-map [:context]
                         #(if (seq %) % context))))
          (group-combine [[_ action-calls]]
            (->
             (reduce
              #(->
                %
                (update-in [:args] conj (:args %2))
                (context-combine %2))
              (assoc (first action-calls) :args [] :context [])
              action-calls)
             (update-in [:context] #(seq (distinct (filter identity %))))))]

    (->>
     action-plan
     (group-by (juxt :action :action-id))
     (map group-combine))))

(def ^{:doc "Execution specific transforms" :private true :no-doc true}
  execution-transforms
  {:aggregated [group-by-function]
   :collected [group-by-function]})

(def ^{:private true} execution-ordering [:aggregated :in-sequence :collected])
(def ^{:private true} execution-translations {:delayed-crate-fn :in-sequence
                                              :aggregated-crate-fn :aggregated
                                              :collected-crate-fn :collected})

(defn- translate-execution
  [execution]
  (get execution-translations execution execution))

(defn- transform-execution
  "Transform an execution by applying execution-transforms."
  [execution action-plan]
  (if-let [transforms (execution-transforms execution)]
    (reduce #(%2 %1) action-plan transforms)
    action-plan))

(defn- transform-scope-executions
  "Sort an action plan scope into different executions, applying execution
   specific transforms."
  [action-plan]
  {:pre [(action-plan? action-plan)]}
  (let [f (comp translate-execution action-map-execution)
        executions (group-by f action-plan)]
    (mapcat
     #(transform-execution % (% executions))
     execution-ordering)))

(defn- transform-executions
  "Sort an action plan into different executions, applying execution specific
   transforms."
  [action-plan]
  {:pre [(action-plan? action-plan)]}
  (walk-action-plan
   identity
   transform-scope-executions
   assoc-blocks
   action-plan))

;;; ## Delayed Crate Functions

;;; Delayed crate functions are called at action-plan translation time.
(declare execute-delayed-crate-fns)

(defn- execute-delayed-crate-fn
  "Execute a delayed crate function"
  [session]
  (fn execute-delayed-crate-fn [action-plan]
    {:pre [(sequential? action-plan)]}
    (letfn [(ex-action-map [{:keys [action args context] :as action-map}]
              (logging/tracef "ex-action-map %s" session)
              (logging/tracef "ex-action-map %s" action-map)
              (if (delayed-execution? (action-execution action))
                ;; execute the delayed phase function
                (with-session session
                  (let [f (-> (action-implementation action :default) :f)
                        _ (if (seq context)
                            (with-context
                              {:kw :ex-with-context :msg "ex-with-context"}
                              (in-phase-context-scope
                               context
                               (apply f args)))
                            (apply f args))
                        [action-plan session] (get-session-action-plan
                                               (pallet.core.session/session))
                        sub-plan (pop-block action-plan)]
                    ;; return the local action-plan
                    (logging/tracef "local action plan is %s" (vec sub-plan))
                    sub-plan))
                ;; return the unmodified action in a vector
                [action-map]))
            (ex [action-plan]
              (if (map? action-plan)
                (ex-action-map action-plan)
                ((execute-delayed-crate-fn session) action-plan)))]
      (mapcat ex action-plan))))

(defn- execute-delayed-crate-fns
  "Walk the action plan, executing any :delayed-crate-fn actions, and replacing
   them with the action-plan that they generate."
  [action-plan session]
  (walk-action-plan
   identity
   (execute-delayed-crate-fn session)
   assoc-blocks
   action-plan))

;;; ### Enforce Declared Precedence Rules

;;; Reorders action maps according to declared precedence rules.
(defn- assoc-action-id
  "Extract an action's id to function mapping"
  [m action-map]
  (if-let [id (:action-id action-map)]
    (assoc m id (action-symbol (:action action-map)))
    m))

(defn- merge-union
  "Merge-with clojure.set/union"
  [& m]
  (apply merge-with union m))

(defn- action-map-id
  [action-map]
  (assoc (select-keys action-map [:action-id])
    :action-symbol (-> action-map :action action-symbol)))

(defn- action-dependencies
  "Extract an action's dependencies.  Actions are id'd with keywords
   and dependencies are declared on an action's id or symbol. action-id-map is
   a map of known ids to symbols."
  [action-id-map action]
  (let [as-set (fn [x] (if (or (nil? x) (set? x)) x #{x}))
        before (as-set (:always-before action))
        after (as-set (:always-after action))
        self-id (action-map-id action)
        a->s #(-> % meta :action :action-symbol)]
    (reduce
     (fn [m [id deps]] (update-in m [id] #(conj (or % #{}) deps)))
     {}
     (concat
      ;; before action inserter
      (map
       #(vector {:action-symbol (a->s %)} self-id)
       (filter fn? before))
      ;; before id
      (map
       #(vector {:action-id % :action-symbol (action-id-map %)} self-id)
       (filter keyword? before))
      ;; after action inserter
      (map
       #(vector self-id {:action-symbol (a->s %)})
       (filter fn? after))
      ;; after id
      (map
       #(vector self-id {:action-id % :action-symbol (action-id-map %)})
       (filter keyword? after))))))

(defn- action-instances
  "Given a map of dependencies, each with an :action-symbol and maybe
   an :action-id, returns a map where the values are all matching action
   instances."
  [actions dependencies]
  (let [action-id-maps (reduce union (vals dependencies))]
    (reduce
     (fn [instances instance]
       (let [id (action-map-id instance)]
         (if (action-id-maps id)
           (update-in instances [id] #(conj (or % #{}) instance))
           instances)))
     {}
     actions)))

(defn- action-scope-dependencies
  "Given a sequence of action maps, return a vector containing:
    - a map from id to symbol,
    - a map from symbol and possibly id, to a set of action identifiers
    - a set of all action identifiers
    - a map from action identifier."
  [actions]
  (let [action-id-map (reduce assoc-action-id {} actions)
        dependencies (reduce
                      #(merge-union %1 (action-dependencies action-id-map %2))
                      {} actions)
        instances (action-instances actions dependencies)
        dependents (zipmap (keys dependencies)
                           (map
                            (fn [d] (set (mapcat instances d)))
                            (vals dependencies)))]
    (logging/tracef
     "action-scope-dependencies %s"
     [actions action-id-map dependencies instances dependents])
    [action-id-map dependencies instances dependents]))

(defn- action-with-dependents
  [actions dependents seen action-map]
  {:pre [(vector? actions) (set? seen) (map? action-map)]}
  (if (seen action-map)
    [actions dependents seen]
    (let [ids (distinct [(action-map-id action-map)
                         (-> action-map :action action-symbol)])
          action-deps (mapcat dependents ids)]
      (let [[add-actions dependents seen]
            (reduce
             (fn add-a-w-d [[actions dependents seen] action-map]
               {:pre [(vector? actions) (set? seen) (map? action-map)]}
               (if (seen action-map)
                 [actions dependents seen]
                 (action-with-dependents actions dependents seen action-map)))
             [actions (reduce dissoc dependents ids) seen]
             action-deps)]
        [(conj add-actions action-map) dependents (conj seen action-map)]))))

(defn- enforce-scope-dependencies
  [actions]
  (let [[action-id-map dependencies instances dependents]
        (action-scope-dependencies actions)]
    (first (reduce
            (fn add-as-w-d [[actions dependents seen] action]
              {:pre [(vector? actions) (set? seen) (map? action)]}
              (if (seen action)
                [actions dependents seen]
                (action-with-dependents actions dependents seen action)))
            [[] dependents #{}]
            actions))))

(defn- enforce-precedence
  "Enforce precedence relations between actions."
  [action-plan]
  (walk-action-plan
   identity
   enforce-scope-dependencies
   assoc-blocks
   action-plan))

;;; ## Translate Action Plan
(defn translate
  "Process the action-plan, applying groupings and precedence, producing
   an action plan with fully bound functions, ready for execution.

   This is equivalent to using an identity monad with a monadic value
   that is a tree of action maps."
  [action-plan session]
  (logging/tracef "translate %s" (count action-plan))
  [(->
    action-plan
    pop-block ;; pop the default block
    transform-executions
    (execute-delayed-crate-fns session)
    enforce-precedence)
   (dissoc-action-plan session)])

;;; ## Node Value Path Lookup
(defn- find-node-value-path
  [action-plan action]
  (letfn [(find-from-action [action-map]
            (when (= (:action action) (:action action-map))
              (:node-value-path action-map)))
          (find-from-actions [action-plan]
            (first (filter identity action-plan)))
          (first-identity [a s]
            s)]
    (when action-plan
      (walk-action-plan
       find-from-action
       find-from-actions
       first-identity
       action-plan))))

;;; ## Execute Action Plan
(defmonad
  ^{:private true
    :no-doc true}
  short-circuiting-state-m
  "Monad describing stateful computations. The monadic values have the
    structure (fn [old-state] [result new-state]). If result is a map with
    :action-plan/flag set to :action-plan/stop, then further calculations are
    short circuited."
  [m-result (fn m-result-state [v]
              (fn [s] [v s]))
   m-bind    (fn m-bind-short-state [mv f]
               (fn [s]
                 (let [[v ss] (mv s)]
                   (if (and (map? v) (= ::stop (::flag v)))
                     [v ss]
                     ((f v) ss)))))])

(def
  ^{:doc
    "The pallet action execution monad. This is fundamentally a state monad,
     where the state is the pallet session map."
    :private true
    :no-doc true}
  action-exec-m short-circuiting-state-m)

(defn translated?
  "Predicate to test if an action plan has been translated"
  [action-plan]
  {:pre [(action-plan? action-plan)]}
  (not (and (= 2 (count action-plan))
            (list? (first action-plan))
            (nil? (second action-plan)))))

;;; ### Argument Evaluation
(defn- evaluate-args
  "Evaluate an argument sequence"
  [session args]
  (map (fn [arg] (when arg (argument/evaluate arg session))) args))

(defn evaluate-arguments
  [session {:keys [args] :as action-map}]
  (logging/tracef "evaluate-arguments")
  (assoc action-map
    :args (case (translate-execution (action-map-execution action-map))
            :in-sequence (evaluate-args session args)
            (map #(evaluate-args session %) args))))

;;; ### Node Values
(defn- set-node-value-with-return-value
  [node-value-path [rv session]]
  (logging/tracef "set-node-value %s %s" node-value-path rv)
  [rv (set-node-value session rv node-value-path)])

;;; ### Defining context
;;; The defining context is the phase context when the phase function is called.
;;; It is made available at action execution via *defining-context*.
(def
  ^{:doc "Phase contexts when action was called in a phase"
    :dynamic true
    :no-doc true}
  *defining-context*)

(defn defining-context-string
  "Returns a context string for the defining phase context."
  []
  (context-string *defining-context*))

;;; ### Action Map Execution
(defn execute-action-map
  "Execute a single action, catching any exception and reporting it as
   an error map."
  [executor session {:keys [node-value-path context] :as action}]
  (logging/tracef "execute-action-map %s %s %s" session action executor)
  (try
    (binding [*defining-context* context]
      (->>
       action
       (evaluate-arguments session)
       (executor session)
       ((fn [r] (logging/tracef "rv is %s" r) r))
       (set-node-value-with-return-value node-value-path)))
    (catch Exception e
      (logging/errorf e "Exception in execute-action-map")
      [{:error {:type :pallet/action-execution-error
                :context (context/contexts)
                :message (format "Unexpected exception: %s" (.getMessage e))
                ;; :location (with-out-str (print-cause-trace e))
                :cause e}}
       session])))

(defn stop-execution-on-error
  ":execute-status-fn algorithm to stop execution on an error"
  [[result session]]
  (if-let [flag (::flag result)]
    (if (not= flag ::stop)
      (if (:error result)
        (do
          (logging/errorf "Stopping execution %s" (:error result))
          [(assoc result ::flag ::stop) session])
        session)
      [result session])
    [result session]))

(defn exec-action
  [executor execute-status-fn]
  (fn exec-action-action [action]
    (fn exec-action-session [session]
      (logging/tracef "exec-action %s %s %s" session action executor)
      (execute-status-fn (execute-action-map executor session action)))))

;;; ### Action Map Executor Functions
;;; Functions for use in executors.
(defn execute-if
  "Execute an if action"
  [session {:keys [blocks] :as action} value]
  (let [executor (get-in session [:action-plans ::executor])
        execute-status-fn (get-in session [:action-plans ::execute-status-fn])
        exec-action (exec-action executor execute-status-fn)]
    (assert executor)
    (assert execute-status-fn)
    (logging/tracef "execute-if value %s" (pr-str value))
    (if value
      ((domonad action-exec-m [v (m-map exec-action (first blocks))] (last v))
       session)
      (if-let [else (seq (second blocks))]
        ((domonad action-exec-m [v (m-map exec-action else)] (last v))
         session)
        [nil session]))))

;;; ### Action Plan Execution
(defn execute
  [action-plan session executor execute-status-fn]
  (logging/tracef
   "execute %s actions with %s %s"
   (count action-plan) executor execute-status-fn)
  (when-not (translated? action-plan)
    (throw
     (ex-info
      "Attempt to execute an untranslated action plan"
      {:type :pallet/execute-called-on-untranslated-action-plan})))
  (letfn [(exec-action [action]
            (fn execute-with-error-check [session]
              (logging/tracef "execute-with-error-check")
              (execute-status-fn
               (execute-action-map executor session action))))]
    ((domonad action-exec-m [v (m-map exec-action action-plan)] v)
     ;; the executor and execute-status-fn are put into the session map in order
     ;; to allow access to them in flow action execution
     (update-in
      session [:action-plans]
      assoc ::executor executor ::execute-status-fn execute-status-fn))))

;;; ## Scope and Context Functions
(defmacro checked-script
  "Return a stevedore script that uses the current context to label the
   action"
  [name & script]
  `(stevedore/checked-script
    (str
     (context-string *defining-context*)
     ~name)
    ~@script))

(defmacro checked-commands*
  "Return a stevedore script that uses the current context to label the
   action"
  [name scripts]
  `(stevedore/checked-commands*
    (str
     (context-string (if (bound? #'*defining-context*) *defining-context* []))
     ~name)
    ~scripts))

(defn checked-commands
  "Return a stevedore script that uses the current context to label the
   action"
  [name & script]
  (checked-commands* name script))
