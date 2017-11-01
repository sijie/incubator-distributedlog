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

package org.apache.distributedlog.statestore.api.mvcc;

import org.apache.bookkeeper.common.annotation.InterfaceAudience.Public;
import org.apache.bookkeeper.common.annotation.InterfaceStability.Evolving;
import org.apache.distributedlog.statestore.api.StateStore;
import org.apache.distributedlog.statestore.api.mvcc.op.OpFactory;

/**
 * A mvcc store that supports synchronous operations.
 *
 * @param <K> key type
 * @param <V> value type
 */
@Public
@Evolving
public interface MVCCStore<K, V> extends StateStore, MVCCStoreWriteView<K, V>, MVCCStoreReadView<K, V> {

    /**
     * Return the operator factory to build operators.
     *
     * @return operator factory.
     */
    OpFactory<K, V> getOpFactory();

}