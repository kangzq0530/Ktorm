/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.liuwj.ktorm.global

import me.liuwj.ktorm.database.*
import me.liuwj.ktorm.logging.Logger
import me.liuwj.ktorm.logging.detectLoggerImplementation
import org.springframework.dao.DataAccessException
import org.springframework.transaction.annotation.Transactional
import java.sql.Connection
import java.sql.SQLException
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

@PublishedApi
internal val lastConnected = AtomicReference<Database>()

@PublishedApi
internal val threadLocal = ThreadLocal<Database>()

/**
 * The global database instance, Ktorm uses this property to obtain a database when any SQL is executed.
 *
 * By default, it's the lasted connected one, but it may change if the [invoke] operator is used.
 *
 * Note that you must connect to the database via [Database.Companion.connectGlobally] first, otherwise an
 * exception will be thrown.
 *
 * @see Database.invoke
 */
val Database.Companion.global: Database get() {
    return threadLocal.get() ?: lastConnected.get() ?: error("Not connected to any database yet.")
}

/**
 * Connect to a database by a specific [connector] function and save the returned database instance
 * to [Database.Companion.global].
 *
 * @param dialect the dialect, auto detects an implementation by default using JDK [ServiceLoader] facility.
 * @param logger logger used to output logs, auto detects an implementation by default.
 * @param connector the connector function used to obtain SQL connections.
 * @return the new-created database object.
 */
fun Database.Companion.connectGlobally(
    dialect: SqlDialect = detectDialectImplementation(),
    logger: Logger = detectLoggerImplementation(),
    connector: () -> Connection
): Database {
    return connect(dialect, logger, connector).also { lastConnected.set(it) }
}

/**
 * Connect to a database using a [DataSource] and save the returned database instance to [Database.Companion.global].
 *
 * @param dataSource the data source used to obtain SQL connections.
 * @param dialect the dialect, auto detects an implementation by default using JDK [ServiceLoader] facility.
 * @param logger logger used to output logs, auto detects an implementation by default.
 * @return the new-created database object.
 */
fun Database.Companion.connectGlobally(
    dataSource: DataSource,
    dialect: SqlDialect = detectDialectImplementation(),
    logger: Logger = detectLoggerImplementation()
): Database {
    return connect(dataSource, dialect, logger).also { lastConnected.set(it) }
}

/**
 * Connect to a database using the specific connection arguments and save the returned database instance
 * to [Database.Companion.global].
 *
 * @param url the URL of the database to be connected.
 * @param driver the full qualified name of the JDBC driver class.
 * @param user the user name of the database.
 * @param password the password of the database.
 * @param dialect the dialect, auto detects an implementation by default using JDK [ServiceLoader] facility.
 * @param logger logger used to output logs, auto detects an implementation by default.
 * @return the new-created database object.
 */
fun Database.Companion.connectGlobally(
    url: String,
    driver: String? = null,
    user: String? = null,
    password: String? = null,
    dialect: SqlDialect = detectDialectImplementation(),
    logger: Logger = detectLoggerImplementation()
): Database {
    return connect(url, driver, user, password, dialect, logger).also { lastConnected.set(it) }
}

/**
 * Connect to a database using a [DataSource] with the Spring support enabled and save the returned database
 * instance to [Database.Companion.global].
 *
 * Once the Spring support is enabled, the transaction management will be delegated to the Spring framework,
 * so the [useTransaction] function is not available anymore, we need to use Spring's [Transactional]
 * annotation instead.
 *
 * This function also enables the exception translation, which can convert any [SQLException] thrown by JDBC
 * to Spring's [DataAccessException] and rethrow it.
 *
 * @param dataSource the data source used to obtain SQL connections.
 * @param dialect the dialect, auto detects an implementation by default using JDK [ServiceLoader] facility.
 * @param logger logger used to output logs, auto detects an implementation by default.
 * @return the new-created database object.
 */
fun Database.Companion.connectWithSpringSupportGlobally(
    dataSource: DataSource,
    dialect: SqlDialect = detectDialectImplementation(),
    logger: Logger = detectLoggerImplementation()
): Database {
    return connectWithSpringSupport(dataSource, dialect, logger).also { lastConnected.set(it) }
}

/**
 * Execute the callback function using the current database instance.
 *
 * Useful when we have many database instances. Call this function to choose one to execute
 * our database specific operations. While the callback functions are executing, the [Database.Companion.global]
 * property will be set to the current database. And after the callback completes, it's automatically
 * restored to the origin one.
 *
 * @see Database.Companion.global
 */
inline operator fun <T> Database.invoke(func: Database.() -> T): T {
    val origin = threadLocal.get()

    try {
        threadLocal.set(this)
        return this.func()
    } catch (e: SQLException) {
        throw exceptionTranslator?.invoke(e) ?: e
    } finally {
        origin?.let { threadLocal.set(it) } ?: threadLocal.remove()
    }
}
