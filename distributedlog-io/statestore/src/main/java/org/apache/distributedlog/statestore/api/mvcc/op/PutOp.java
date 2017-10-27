/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.distributedlog.statestore.api.mvcc.op;

import org.apache.bookkeeper.common.annotation.InterfaceAudience.Public;
import org.apache.bookkeeper.common.annotation.InterfaceStability.Evolving;

/**
 * A put operator that update a value associated with a given key.
 *
 * @param <K> key type
 * @param <V> value type
 */
@Public
@Evolving
public interface PutOp<K, V> extends Op<K, V> {

    V value();

    boolean prevKV();

    /**
     * Builder to build a put operator.
     */
    interface Builder<K, V> extends OpBuilder<K, V, PutOp<K, V>, Builder<K, V>> {

        Builder<K, V> value(V value);

        Builder<K, V> prevKV(boolean prevKV);

    }

}
