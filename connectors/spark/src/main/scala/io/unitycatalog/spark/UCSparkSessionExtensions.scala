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
import org.apache.spark.sql.catalyst.parser.extensions.UCSparkSqlExtensionsParser

/**
 * Spark session extensions for bare cloud-path credential vending.
 *
 * [[ResolvePathCredentials]] is registered as a hint resolution rule rather than only a resolution
 * rule so it runs before `ResolveSQLOnFile` lists the path for schema inference. For `delta.`path``
 * the same hint-batch pass early-resolves the relation with options intact. A resolution-batch
 * pass then patches Delta-specific nodes if Delta rewrote the tree without those options. The
 * parser also applies the idempotent rule as a fallback for Spark's Hive analyzer, which omits
 * extension-provided hint rules and is used by Hive Thrift Server.
 */
class UCSparkSessionExtensions extends (SparkSessionExtensions => Unit) {

  override def apply(extensions: SparkSessionExtensions): Unit = {
    extensions.injectParser { case (spark, parser) =>
      new UCSparkSqlExtensionsParser(spark, parser)
    }
    extensions.injectHintResolutionRule { spark =>
      ResolvePathCredentials(spark, resolveDeltaPathRelations = true)
    }
    extensions.injectResolutionRule { spark =>
      ResolvePathCredentials(spark, resolveDeltaPathRelations = false)
    }
  }
}
