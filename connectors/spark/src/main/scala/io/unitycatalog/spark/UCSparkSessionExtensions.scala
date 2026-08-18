/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.unitycatalog.spark

import org.apache.spark.sql.SparkSessionExtensions

/**
 * Spark session extensions for UC view DDL routing and bare cloud-path credential vending.
 *
 * [[ResolvePathCredentials]] is registered as a hint resolution rule so it runs before
 * `ResolveSQLOnFile` lists the path for schema inference. View DDL continues to use the parser
 * extension so it can be routed to REST before Spark rejects catalogs without `ViewCatalog`.
 */
class UCSparkSessionExtensions
    extends (SparkSessionExtensions => Unit)
    with UCSparkSessionExtensionsViewSupport {

  override def apply(extensions: SparkSessionExtensions): Unit = {
    extensions.injectParser { case (spark, parser) =>
      new UCSparkSqlExtensionsParser(spark, parser)
    }
    extensions.injectHintResolutionRule(ResolvePathCredentials(_))
    injectViewRules(extensions)
  }
}
