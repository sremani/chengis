(ns chengis.engine.log-context
  "MDC-like correlation context for structured logging.
   Wraps Timbre's with-context to add build-id, job-id, org-id,
   stage-name, and step-name to all log entries within scope.

   These context keys appear in JSON log output and enable
   log aggregation tools (ELK, Loki, Datadog) to correlate
   log lines across a single build execution.

   All macros merge with the existing context so they can be nested
   (e.g. build → stage → step) without losing parent keys."
  (:require [taoensso.timbre :as log]))

(defmacro with-build-context
  "Add build-level correlation context to all log entries within body.
   Keys added: :build-id, :job-id, :org-id
   Merges with any existing context."
  [build-id job-id org-id & body]
  `(log/with-context (merge log/*context*
                            {:build-id ~build-id
                             :job-id ~job-id
                             :org-id ~org-id})
     ~@body))

(defmacro with-stage-context
  "Add stage-level correlation context to all log entries within body.
   Keys added: :stage-name
   Typically nested inside with-build-context. Merges with existing context."
  [stage-name & body]
  `(log/with-context (merge log/*context*
                            {:stage-name ~stage-name})
     ~@body))

(defmacro with-step-context
  "Add step-level correlation context to all log entries within body.
   Keys added: :step-name
   Typically nested inside with-stage-context. Merges with existing context."
  [step-name & body]
  `(log/with-context (merge log/*context*
                            {:step-name ~step-name})
     ~@body))

(defmacro with-trace-context
  "Add trace-level correlation context (trace-id, span-id) to log entries.
   Used when tracing is enabled to correlate logs with trace spans.
   Merges with existing context."
  [trace-id span-id & body]
  `(log/with-context (merge log/*context*
                            {:trace-id ~trace-id
                             :span-id ~span-id})
     ~@body))
