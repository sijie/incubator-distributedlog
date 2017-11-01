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

import java.util.Optional;
import org.apache.bookkeeper.common.annotation.InterfaceAudience.Public;
import org.apache.bookkeeper.common.annotation.InterfaceStability.Evolving;

/**
 * A range operation.
 */
@Public
@Evolving
public interface RangeOp<K, V> extends Op<K, V> {

    Optional<K> key();

    Optional<K> endKey();

    /**
     * If this op is a range operation or a get operation.
     *
     * @return true if it is a range operation, otherwise it is a get operation.
     */
    boolean isRangeOp();

    int limit();

    long minModRev();

    long maxModRev();

    long minCreateRev();

    long maxCreateRev();

}