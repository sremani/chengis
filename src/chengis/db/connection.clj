(ns chengis.db.connection
  (:require [next.jdbc :as jdbc]))

(defn create-datasource
  "Create a SQLite datasource from a database path."
  [db-path]
  (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path}))

(defn test-connection
  "Verify the database connection works."
  [ds]
  (jdbc/execute-one! ds ["SELECT 1 AS result"]))

(defn close-datasource!
  "Close the datasource connection if it implements Closeable.
   No-op for simple SQLite datasources, but provides a clean API for shutdown."
  [ds]
  (when (instance? java.io.Closeable ds)
    (.close ^java.io.Closeable ds)))
